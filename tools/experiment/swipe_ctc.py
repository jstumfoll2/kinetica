"""Phase 3 experiment: a small CTC swipe encoder trained from scratch.

Why from scratch: the plan named Compose Keyboard's pipeline on FUTO's dataset,
but neither is reachable from the environment this was written in (Hugging Face
is blocked) and CONTRIBUTING.md bans third-party prediction components. This
file is the whole pipeline in one place - synthetic data, model, training,
export - so it can be pointed at real data later without new moving parts.

Input per resampled point (RESAMPLE_N = 32, uniform arc length, like
DtwMatcher.resample): a gaussian proximity to each of the 26 letter-key centres
plus the step (dx, dy). Per-letter proximity rather than raw x/y is what lets
one model serve any layout: the label space is letters, and so is the input.

Output: log-probabilities over blank + a..z + apostrophe (28 classes) per point.
The keyboard uses it as a CTC cost on each swipe piece of a candidate word.

Usage:
  python swipe_ctc.py train --out model.kctc [--steps 6000]
  python swipe_ctc.py eval --model model.kctc
"""
import argparse
import math
import os
import random
import struct
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

N = 32                    # KineticaConstants.RESAMPLE_N
SIGMA_KW = 0.5            # proximity feature width, key widths
CLASSES = 28              # blank, a..z, apostrophe
IN_DIM = 26 + 2
HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets")

# QWERTY letter block in kw, matching assets/layouts/qwerty.json proportions
# (keys 1.0 wide, rows 1.5 tall) and TestData.qwertyGeometry.
ROWS = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)]


def qwerty(row_pitch=1.5):
    c = {}
    for r, (letters, off) in enumerate(ROWS):
        for i, ch in enumerate(letters):
            c[ch] = (off + i + 0.5, r * row_pitch + row_pitch / 2)
    return c


def code(ch):
    return 27 if ch == "'" else ord(ch) - ord("a") + 1


def features(pts, centres):
    """pts: (N, 2) kw. Returns (N, IN_DIM) float32. Mirrors CtcScorer.features."""
    keys = np.array([centres[chr(ord("a") + k)] for k in range(26)], dtype=np.float32)
    d2 = ((pts[:, None, :] - keys[None, :, :]) ** 2).sum(-1)
    prox = np.exp(-d2 / (2 * SIGMA_KW * SIGMA_KW))
    step = np.zeros_like(pts)
    step[1:] = pts[1:] - pts[:-1]
    return np.concatenate([prox, step], axis=1).astype(np.float32)


def resample(poly, n=N):
    """Uniform arc-length resampling, the same walk as DtwMatcher.resample."""
    poly = np.asarray(poly, dtype=np.float64)
    if len(poly) == 1:
        return np.repeat(poly, n, axis=0).astype(np.float32)
    seg = np.sqrt(((poly[1:] - poly[:-1]) ** 2).sum(1))
    arc = seg.sum()
    if arc <= 1e-6:
        return np.repeat(poly[:1], n, axis=0).astype(np.float32)
    acc = np.concatenate([[0], np.cumsum(seg)])
    t = np.linspace(0, arc, n)
    x = np.interp(t, acc, poly[:, 0])
    y = np.interp(t, acc, poly[:, 1])
    return np.stack([x, y], 1).astype(np.float32)


# ------------------------------------------------------------------ synthetic data

def load_words(lang="en", limit=20000):
    out = []
    with open(os.path.join(ASSETS, "dictionaries", f"{lang}_wordlist.txt"), encoding="utf-8") as f:
        for line in f:
            w = line.split("\t", 1)[0].strip()
            if 1 <= len(w) <= 14 and all("a" <= c <= "z" or c == "'" for c in w):
                out.append(w)
            if len(out) >= limit:
                break
    return out


def synth_path(letters, centres, rnd, lead=None):
    """A sloppy thumb path through the keys of `letters` (kw, dense)."""
    keys = [c for c in letters if c != "'"]
    dedup = [k for i, k in enumerate(keys) if i == 0 or k != keys[i - 1]]
    sigma = rnd.uniform(0.05, 0.35)
    verts = []
    if lead is not None:
        verts.append(centres[lead])
    for k in dedup:
        x, y = centres[k]
        verts.append((x + rnd.gauss(0, sigma), y + rnd.gauss(0, sigma * 1.2)))
    if len(verts) == 1:
        x, y = verts[0]
        verts.append((x + rnd.gauss(0, 0.15), y + rnd.gauss(0, 0.15)))
    # Overshoot at the ends: a lifted thumb rarely stops dead on the key.
    if rnd.random() < 0.5:
        (ax, ay), (bx, by) = verts[-2], verts[-1]
        f = rnd.uniform(0.0, 0.25)
        verts.append((bx + f * (bx - ax), by + f * (by - ay)))
    dense = []
    for (ax, ay), (bx, by) in zip(verts[:-1], verts[1:]):
        steps = max(3, int(math.hypot(bx - ax, by - ay) * 6))
        for s in range(steps):
            f = s / steps
            dense.append((ax + f * (bx - ax), ay + f * (by - ay)))
    dense.append(verts[-1])
    dense = np.array(dense)
    # Corner cutting: a moving average rounds every turn, as thumbs do.
    w = rnd.choice([1, 3, 5, 7])
    if w > 1 and len(dense) > w:
        k = np.ones(w) / w
        sm = np.stack([np.convolve(dense[:, i], k, mode="same") for i in range(2)], 1)
        sm[: w // 2] = dense[: w // 2]
        sm[-(w // 2):] = dense[-(w // 2):]
        dense = sm
    dense += np.random.default_rng(rnd.randrange(1 << 30)).normal(0, 0.03, dense.shape)
    return dense


def sample(words, centres, rnd, frag_p=0.5):
    """One (features, label) pair: a whole word, or a fragment of one as a thumb piece."""
    w = words[min(int(rnd.paretovariate(1.1)) - 1, len(words) - 1)] if rnd.random() < 0.5 else rnd.choice(words)
    label = w
    lead = None
    if len(w) >= 3 and rnd.random() < frag_p:
        i = rnd.randrange(0, len(w) - 1)
        j = rnd.randrange(i + 2, len(w) + 1)
        label = w[i:j]
        # A piece cut mid-gesture starts with travel from the previous letter.
        if i > 0 and w[i - 1] != "'" and rnd.random() < 0.3:
            lead = w[i - 1]
    pts = resample(synth_path(label, centres, rnd, lead))
    return features(pts, centres), [code(c) for c in label], label


# ------------------------------------------------------------------ model

class Encoder(nn.Module):
    """Two convolutions then one bidirectional GRU; CtcScorer.kt runs the same graph."""

    def __init__(self, width=64, hidden=64):
        super().__init__()
        self.conv1 = nn.Conv1d(IN_DIM, width, 5, padding=2)
        self.conv2 = nn.Conv1d(width, width, 5, padding=2)
        self.gru = nn.GRU(width, hidden, batch_first=True, bidirectional=True)
        self.out = nn.Linear(2 * hidden, CLASSES)

    def forward(self, x):                      # x: (B, N, IN_DIM)
        h = x.transpose(1, 2)
        h = F.relu(self.conv1(h))
        h = F.relu(self.conv2(h))
        h, _ = self.gru(h.transpose(1, 2))
        return F.log_softmax(self.out(h), -1)  # (B, N, CLASSES)


def export(model, path):
    """Flat little-endian file: b'KCTC', version, then each tensor as ndim, dims, float32s."""
    names = [
        "conv1.weight", "conv1.bias", "conv2.weight", "conv2.bias",
        "gru.weight_ih_l0", "gru.weight_hh_l0", "gru.bias_ih_l0", "gru.bias_hh_l0",
        "gru.weight_ih_l0_reverse", "gru.weight_hh_l0_reverse",
        "gru.bias_ih_l0_reverse", "gru.bias_hh_l0_reverse",
        "out.weight", "out.bias",
    ]
    sd = model.state_dict()
    with open(path, "wb") as f:
        f.write(b"KCTC")
        f.write(struct.pack("<ii", 1, len(names)))
        f.write(struct.pack("<f", SIGMA_KW))
        for n in names:
            t = sd[n].detach().cpu().numpy().astype("<f4")
            f.write(struct.pack("<i", t.ndim))
            f.write(struct.pack(f"<{t.ndim}i", *t.shape))
            f.write(t.tobytes())


def load(path):
    m = Encoder()
    sd = m.state_dict()
    with open(path, "rb") as f:
        assert f.read(4) == b"KCTC"
        _, count = struct.unpack("<ii", f.read(8))
        struct.unpack("<f", f.read(4))
        names = list(sd.keys())
        order = [
            "conv1.weight", "conv1.bias", "conv2.weight", "conv2.bias",
            "gru.weight_ih_l0", "gru.weight_hh_l0", "gru.bias_ih_l0", "gru.bias_hh_l0",
            "gru.weight_ih_l0_reverse", "gru.weight_hh_l0_reverse",
            "gru.bias_ih_l0_reverse", "gru.bias_hh_l0_reverse",
            "out.weight", "out.bias",
        ]
        for n in order[:count]:
            nd = struct.unpack("<i", f.read(4))[0]
            dims = struct.unpack(f"<{nd}i", f.read(4 * nd))
            a = np.frombuffer(f.read(4 * int(np.prod(dims))), dtype="<f4").reshape(dims)
            sd[n] = torch.from_numpy(a.copy())
    m.load_state_dict(sd)
    return m


# ------------------------------------------------------------------ train / eval

def batch(words, rnd, size, pitches):
    xs, ys, lens = [], [], []
    for _ in range(size):
        x, y, _ = sample(words, qwerty(rnd.choice(pitches)), rnd)
        xs.append(x)
        ys.extend(y)
        lens.append(len(y))
    return torch.from_numpy(np.stack(xs)), torch.tensor(ys), torch.tensor(lens)


def greedy(lp):
    best = lp.argmax(-1).tolist()
    out, prev = [], 0
    for b in best:
        if b != prev and b != 0:
            out.append("'" if b == 27 else chr(ord("a") + b - 1))
        prev = b
    return "".join(out)


def evaluate(model, words, seed=12345, n=2000):
    rnd = random.Random(seed)
    model.eval()
    whole = frag = whole_ok = frag_ok = 0
    with torch.no_grad():
        for _ in range(n):
            x, _, label = sample(words, qwerty(rnd.choice([1.5, 1.9])), rnd)
            got = greedy(model(torch.from_numpy(x)[None])[0])
            is_whole = label in words
            if is_whole:
                whole += 1
                whole_ok += got == label
            else:
                frag += 1
                frag_ok += got == label
    return whole_ok / max(whole, 1), frag_ok / max(frag, 1), whole, frag


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["train", "eval"])
    ap.add_argument("--out", default=os.path.join(HERE, "model.kctc"))
    ap.add_argument("--model", default=os.path.join(HERE, "model.kctc"))
    ap.add_argument("--steps", type=int, default=6000)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 1)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    words = load_words()
    if a.cmd == "eval":
        w, fr, nw, nf = evaluate(load(a.model), words)
        print(f"greedy exact: whole words {w:.3f} (n={nw}), fragments {fr:.3f} (n={nf})")
        return
    rnd = random.Random(a.seed)
    model = Encoder()
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=a.steps)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    print(f"params: {sum(p.numel() for p in model.parameters())}", flush=True)
    for step in range(1, a.steps + 1):
        model.train()
        x, y, lens = batch(words, rnd, a.batch, [1.5, 1.7, 1.9])
        lp = model(x).transpose(0, 1)              # (N, B, C)
        loss = ctc(lp, y, torch.full((x.shape[0],), N, dtype=torch.long), lens)
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        if step % 500 == 0 or step == a.steps:
            w, fr, _, _ = evaluate(model, words, n=500)
            print(f"step {step} loss {loss.item():.3f} greedy whole {w:.3f} frag {fr:.3f}", flush=True)
            export(model, a.out)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    sys.exit(main())
