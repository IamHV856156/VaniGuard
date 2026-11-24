
from datasets import load_dataset
from collections import Counter

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 65)
print("VANIGUARD - TRANSCRIPT QUALITY CHECK")
print("=" * 65)

dataset = load_dataset(DATASET_ID)

for split_name, data in dataset.items():
    print(f"\n{split_name.upper()}")

    total = len(data)
    empty = 0
    whitespace_only = 0
    valid = 0
    lengths = []

    for text in data["cumulative_text"]:
        if text is None:
            empty += 1
        elif not text.strip():
            whitespace_only += 1
        else:
            valid += 1
            lengths.append(len(text))

    print(f"Total chunks: {total:,}")
    print(f"Null transcripts: {empty:,}")
    print(f"Empty/whitespace transcripts: {whitespace_only:,}")
    print(f"Valid transcripts: {valid:,}")

    if lengths:
        lengths.sort()
        print(f"Minimum length: {lengths[0]:,}")
        print(f"Median length: {lengths[len(lengths) // 2]:,}")
        print(f"Maximum length: {lengths[-1]:,}")

    print("\nSample transcripts:")

    shown = 0
    for text in data["cumulative_text"]:
        if text and text.strip():
            print(f"\n{text[:500]}")
            shown += 1

        if shown == 3:
            break

print("\nTranscript quality check completed.")
