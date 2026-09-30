# Trace replay harness

Replays recorded swipe traces through today's decoder and reports how often the
committed word comes out on top. Plain JVM: it needs a JDK, not the Android SDK.

```bash
# Record a synthetic session (useful for trying the tools; not an accuracy figure)
./gradlew -p tools/replay replay --args="synth --words 300 /tmp/synth.jsonl"

# Replay traces: exactness check plus metrics, overall and per bucket
./gradlew -p tools/replay replay --args="replay /tmp/synth.jsonl"

# The engine's JVM suite plus the trace gate, without the Android SDK
./gradlew -p tools/replay test
```

Options: `--k N` sets the deep list for recall (default 50); `--assets DIR`
points at `app/src/main/assets` when run from elsewhere. `replay` exits 1 if any
comparable line fails to reproduce.

## Output

```
lines=229 scored=220 abandoned=9 unreadable=0
exact replay: 229/229 comparable lines (identical list, scores bit for bit)

bucket                  n    top1    top3     MRR    in@10 recall@50
all                   220   98.2%  100.0%   0.991   100.0%     99.1%
...
```

- **exact replay**: the replayed shipping list equals the list the bar showed,
  word for word and score bit for bit. Lines are comparable unless they were
  recorded with personal dictionary state, a replaced wordlist, or committed
  before their last decode landed.
- **top1, top3, MRR, in@10**: from the shipping decode (TOP_K = 10), the list
  the keyboard itself shows. MRR counts 0 when the word is absent.
- **recall@50**: from a second decode at K = 50. That is a different search, not
  a longer copy of the first: a bigger heap loosens early-abandon budgets and
  spends the fixed `MAX_EMIT_ATTEMPTS` budget sooner, so it can lose a word the
  shipping list has. The report counts those lines separately.
- **Buckets**: input shape (exactly one of `single-swipe`, `multi-swipe`,
  `two-thumb-overlap`, `tap+swipe`, `taps`) plus two label attributes that
  overlap them, `double-letter` and `short` (3 letters or fewer).

## Trace format v1

One JSON object per line, one line per word buffer. The code and full field
notes are in `app/src/debug/java/com/kinetica/keyboard/engine/trace/SwipeTrace.kt`.

```json
{"v":1,"type":"word",
 "cfg":{"lang":"en","alt":null,"britishSpelling":false,"personal":false,"dictOverride":false},
 "geom":{"kwPx":108.0,"midPx":540.0,"tapPx":37.8,"keys":{"a":[0.5,1.5,1.5,3.0], "...":[]}},
 "ctx":["of","the"],
 "tokens":[{"src":"ev","s":"L","ev":[[607.69,95.79,10000],[612.1,97.0,10008],[640.2,99.3,10016]]},
           {"src":"tap","s":"L","k":"h","x":6.0,"y":2.25,"lp":false,"t0":10200,"t1":10201}],
 "shown":{"n":2,"c":[["hello",0.83,"en"],["hells",0.12,"en"]]},
 "word":"hello","how":null}
```

- `geom.keys`: letter rects in key widths (left, top, right, bottom). Multiply by
  `kwPx` for pixels.
- `tokens[].src == "ev"`: a gesture as raw samples `[x px, y px, t ms]`, down
  first and lift last, with `s` the thumb stream (L or R) the engine assigned.
  Replay feeds these to a fresh `GestureEngine`, so tap/swipe classification,
  contacts and dwells are replayed too, not trusted from the recording.
- `tap` / `swipe`: tokens that never came from the engine (a word reloaded from
  the editor, an accent-popup letter), stored field for field.
- `shown`: the last list the bar received and how many tokens its decode saw.
- `word`: the committed word, or null for an abandoned buffer. `how` is reserved
  for how it was committed (picked, autocorrected, typed) once the recorder is
  wired into the keyboard.

Floats are written as the shortest decimal that identifies the float. Parse
them straight to float32 to get the recorded bits back.

## Privacy

Every line contains what was typed, so a trace file is personal. `*.jsonl` and
`traces/` are ignored by git; keep it that way. The personal dictionary itself
(learned counts, pairs, user and blocked words) is never recorded; `cfg.personal`
only says whether it influenced the live decode, and the harness always replays
against the bundled dictionaries.

The recorder (`SwipeTraceRecorder`) is in the debug source set only, like the
existing `TraceRecorder`: a release APK contains no trace code.
