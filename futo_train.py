"""Trains the swipe_ctc.py encoder on FUTO's swipe dataset (MIT licensed).

Only the DATASET is used: FUTO's released model weights and their outputs are
never downloaded or used, per the project plan. Runs where Hugging Face is
reachable (the "Train swipe encoder" CI job).

Data: swipe-1 (about 1M single-finger whole-word swipes, pre-filtered) from
train.jsonl; test.jsonl is held out for the report. Points are normalised to
the keyboard canvas; FUTO's qwerty layout puts keys 0.1 of the width apart, so
x / 0.1 is key widths (kw), and y is scaled by the canvas aspect the same way.

Samples mix three sources, since the keyboard scores *pieces* of words (one
thumb's stroke), not only whole words:
  - a real whole-word swipe;
  - a real fragment: the real path cut between letters, where the cut points
    come from a monotone nearest-approach alignment of the word's keys;
  - the synthetic generator's pieces, for lead-in travel and rare shapes.

Usage:
  python futo_train.py --out model.kctc [--rows 600000] [--minutes 240]
"""
import argparse
import json
import os
import random
import sys
import time

import numpy as np
import torch
import torch.nn as nn

from huggingface_hub import hf_hub_download

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import swipe_ctc as sc  # noqa: E402

REPO = "futo-org/swipe.futo.org"
KEY_PITCH = 0.1  # FUTO qwerty.json: centres 0.1 apart in x, rx = 0.05


def futo_centres():
    with open(hf_hub_download(REPO, "swipe-5/layouts/qwerty.json", repo_type="dataset")) as f:
        lay = json.load(f)
    return {k["letter"]: (k["cx"], k["cy"]) for k in lay["keys"]}


def to_kw(xs, ys, w, h):
    """Canvas-normalised points to kw: x by the key pitch, y by the canvas aspect too."""
    return np.stack([np.asarray(xs) / KEY_PITCH, np.asarray(ys) * (h / w) / KEY_PITCH], 1).astype(np.float32)


def load_rows(path, limit, rnd_skip=0.0, seed=0):
    """(word, path_kw, aspect) for clean single-finger swipes of a-z' words."""
    rnd = random.Random(seed)
    out = []
    with open(path) as f:
        for line in f:
            if len(out) >= limit:
                break
            if rnd_skip and rnd.random() < rnd_skip:
                continue
            r = json.loads(line)
            word = r["word"].lower()
            data = r["data"]
            if not isinstance(data, list) or len(data) < 3:
                continue
            if not (1 <= len(word) <= 14) or not all("a" <= c <= "z" or c == "'" for c in word):
                continue
            if len(set(c for c in word if c != "'")) < 2:
                continue  # a tap or a one-key word: no swipe shape to learn
            w, h = r["canvas_width"], r["canvas_height"]
            pts = to_kw([p["x"] for p in data], [p["y"] for p in data], w, h)
            out.append((word, pts, h / w))
    return out


def centres_kw(norm, aspect):
    return {c: (x / KEY_PITCH, y * aspect / KEY_PITCH) for c, (x, y) in norm.items()}


def align(pts, keys, cent):
    """Monotone nearest-approach index per key: DP minimising summed distance."""
    P, L = len(pts), len(keys)
    if L > P:
        return None
    kc = np.array([cent[k] for k in keys], dtype=np.float32)
    d = np.sqrt(((pts[None, :, :] - kc[:, None, :]) ** 2).sum(-1))  # (L, P)
    cost = np.full((L, P), np.inf)
    back = np.zeros((L, P), dtype=np.int64)
    cost[0] = d[0]
    for i in range(1, L):
        best = np.minimum.accumulate(cost[i - 1])
        arg = np.zeros(P, dtype=np.int64)
        m = 0
        for j in range(P):
            if cost[i - 1][j] <= cost[i - 1][m]:
                m = j
            arg[j] = m
        cost[i] = best + d[i]
        back[i] = arg
    idx = [int(np.argmin(cost[-1]))]
    for i in range(L - 1, 0, -1):
        idx.append(int(back[i][idx[-1]]))
    return idx[::-1]


def real_sample(row, norm, rnd, frag):
    word, pts, aspect = row
    cent = centres_kw(norm, aspect)
    if not frag or len(word) < 3:
        return sc.features(sc.resample(pts), cent), [sc.code(c) for c in word], word
    letters = [c for c in word if c != "'"]
    # Key visits: consecutive duplicates share one visit (and one path point).
    visits, owner = [], []
    for i, c in enumerate(letters):
        if not visits or c != visits[-1]:
            visits.append(c)
        owner.append(len(visits) - 1)
    idx = align(pts, visits, cent)
    if idx is None:
        return sc.features(sc.resample(pts), cent), [sc.code(c) for c in word], word
    i = rnd.randrange(0, len(letters) - 1)
    j = rnd.randrange(i + 2, len(letters) + 1)
    a, b = idx[owner[i]], idx[owner[j - 1]]
    if b - a < 2:
        return sc.features(sc.resample(pts), cent), [sc.code(c) for c in word], word
    # Sometimes keep some lead-in travel from the previous key, as a cut piece has.
    if i > 0 and rnd.random() < 0.3:
        a = (idx[owner[i - 1]] + a) // 2
    label = "".join(letters[i:j])
    return sc.features(sc.resample(pts[a:b + 1]), cent), [sc.code(c) for c in label], label


def batch(rows, norm, words, rnd, size, p_real=0.85, p_frag=0.35):
    xs, ys, lens = [], [], []
    for _ in range(size):
        if rnd.random() < p_real:
            x, y, _ = real_sample(rows[rnd.randrange(len(rows))], norm, rnd, rnd.random() < p_frag)
        else:
            x, y, _ = sc.sample(words, sc.qwerty(rnd.choice([1.3, 1.5, 1.7])), rnd)
        xs.append(x)
        ys.extend(y)
        lens.append(len(y))
    return torch.from_numpy(np.stack(xs)), torch.tensor(ys), torch.tensor(lens)


def evaluate(model, rows, norm, n=2000, seed=99):
    rnd = random.Random(seed)
    model.eval()
    res = {"whole": [0, 0], "frag": [0, 0]}
    with torch.no_grad():
        for k in range(min(n, len(rows))):
            frag = k % 2 == 1
            x, _, label = real_sample(rows[k], norm, rnd, frag)
            got = sc.greedy(model(torch.from_numpy(x)[None])[0])
            r = res["frag" if frag and len(rows[k][0]) >= 3 else "whole"]
            r[0] += got == label
            r[1] += 1
    return {k: (v[0] / max(v[1], 1), v[1]) for k, v in res.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--rows", type=int, default=600_000)
    ap.add_argument("--minutes", type=float, default=240)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--width", type=int, default=64)
    ap.add_argument("--hidden", type=int, default=64)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    torch.set_num_threads(os.cpu_count() or 1)
    torch.manual_seed(a.seed)
    rnd = random.Random(a.seed)

    t0 = time.time()
    norm = futo_centres()
    train = load_rows(hf_hub_download(REPO, "train.jsonl", repo_type="dataset"), a.rows)
    test = load_rows(hf_hub_download(REPO, "test.jsonl", repo_type="dataset"), 4000, rnd_skip=0.8, seed=5)
    print(f"loaded {len(train)} train rows, {len(test)} test rows in {time.time() - t0:.0f}s", flush=True)
    words = sc.load_words()

    model = sc.Encoder(a.width, a.hidden)
    print(f"params: {sum(p.numel() for p in model.parameters())}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    budget = a.minutes * 60
    start = time.time()
    step = 0
    while True:
        frac = (time.time() - start) / budget
        if frac >= 1:
            break
        # Cosine decay on wall time, so the budget, not a step count, ends training.
        for g in opt.param_groups:
            g["lr"] = 3e-3 * 0.5 * (1 + np.cos(np.pi * min(frac, 1.0))) + 1e-5
        model.train()
        x, y, lens = batch(train, norm, words, rnd, a.batch)
        lp = model(x).transpose(0, 1)
        loss = ctc(lp, y, torch.full((x.shape[0],), sc.N, dtype=torch.long), lens)
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        step += 1
        if step % 1000 == 0:
            r = evaluate(model, test, norm, n=600)
            print(f"step {step} min {(time.time() - start) / 60:.0f} loss {loss.item():.3f} "
                  f"held-out greedy whole {r['whole'][0]:.3f} frag {r['frag'][0]:.3f}", flush=True)
            sc.export(model, a.out)
    sc.export(model, a.out)
    r = evaluate(model, test, norm, n=len(test))
    print(f"final step {step}: held-out greedy exact whole {r['whole'][0]:.3f} (n={r['whole'][1]}), "
          f"fragments {r['frag'][0]:.3f} (n={r['frag'][1]})", flush=True)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
