# CTC swipe encoder experiment

`swipe_ctc.py` trains the small encoder that `engine/CtcScorer.kt` runs, and
writes its weights as a flat `.kctc` file. It is a development tool: nothing
here ships, and the APK carries no model.

```bash
python -m venv venv && ./venv/bin/pip install -r requirements.txt
./venv/bin/python swipe_ctc.py train --out model.kctc --steps 12000   # CPU: ~45 min on 4 cores
./venv/bin/python swipe_ctc.py eval --model model.kctc

# A/B on replayed traces, from the repo root
./gradlew -p tools/replay replay --args="replay --ctc tools/experiment/model.kctc --beta 0.2 traces.jsonl"
./gradlew -p tools/replay replay --args="tune --ctc tools/experiment/model.kctc a.jsonl b.jsonl"
```

## What it is

- **Input**: one swipe piece resampled to 32 points by arc length, the same
  walk as `DtwMatcher.resample`. Each point is a gaussian proximity to every
  letter key centre (sigma 0.5 kw) plus its step from the previous point.
  Because the features are per letter, not per coordinate, the model is
  layout-agnostic in principle.
- **Model**: Conv1d(28, 64, 5), Conv1d(64, 64, 5), BiGRU(64), Linear to 28
  classes (blank, a-z, apostrophe). About 83 k parameters, 330 KB as float32.
- **Training data**: synthetic only. Paths go through key centres with
  per-vertex gaussian noise, corner cutting (moving average), endpoint
  overshoot, and row pitches 1.5 to 1.9 kw. Half the samples are fragments of
  a word, sometimes with lead-in travel from the previous letter, because that
  is what a two-thumb piece looks like.
- **Keyboard use**: `CtcReranker` multiplies each candidate's score by
  `exp(-beta x cost)`, where cost is the CTC negative log-likelihood of each
  swipe piece's letters, averaged per letter. Beta 0 is today's decode exactly.

## What this is not

This was planned as a reproduction of Compose Keyboard's pipeline on FUTO's
MIT swipe dataset. Neither was reachable from the environment it was written
in (Hugging Face is blocked there), so the encoder is trained from scratch on
synthetic paths. A model trained and judged on synthetic paths says nothing
about real thumbs. The pipeline is built so the data source is the only thing
to swap: replace `sample()` with a loader that yields (resampled kw points,
label) from real swipes, keeping the feature function.

The FUTO Swipe *weights* must not be used to train this model or to label its
data (their licence makes any model trained on their outputs derivative).
FUTO's *dataset* is MIT and fine.
