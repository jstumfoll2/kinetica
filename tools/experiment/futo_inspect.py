"""Prints the layout of FUTO's swipe dataset (MIT): files, schemas, sample rows.

Run where Hugging Face is reachable (the "Train swipe encoder" CI job). Reads
the dataset only; FUTO's model weights are never downloaded.
"""
import os

from huggingface_hub import HfApi, hf_hub_download

REPO = "futo-org/swipe.futo.org"


def main():
    api = HfApi()
    files = api.list_repo_tree(REPO, repo_type="dataset", recursive=True)
    print("== files")
    small = []
    for f in files:
        size = getattr(f, "size", None)
        print(f"{f.path}\t{size}")
        if size is not None and size < 200_000_000 and f.path.endswith((".parquet", ".jsonl", ".json", ".csv", ".md")):
            small.append(f.path)
    for path in [p for p in small if p.endswith(".md")][:2]:
        print(f"== {path}")
        print(open(hf_hub_download(REPO, path, repo_type="dataset")).read()[:6000])
    data = [p for p in small if not p.endswith(".md")]
    for path in data[:3]:
        print(f"== sample of {path}")
        local = hf_hub_download(REPO, path, repo_type="dataset")
        if path.endswith(".parquet"):
            import pyarrow.parquet as pq
            t = pq.read_table(local)
            print(t.schema)
            print("rows", t.num_rows)
            for row in t.slice(0, 2).to_pylist():
                print({k: (str(v)[:600]) for k, v in row.items()})
        else:
            with open(local) as fh:
                for i, line in enumerate(fh):
                    if i >= 2:
                        break
                    print(line[:1200])
        print("bytes", os.path.getsize(local))


if __name__ == "__main__":
    main()
