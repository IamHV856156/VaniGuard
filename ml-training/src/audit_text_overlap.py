
import json
from collections import defaultdict, Counter
from pathlib import Path

DATA_DIR = Path("data/processed")
SPLITS = ["train", "validation", "test"]

text_records = defaultdict(lambda: defaultdict(list))

for split in SPLITS:
    path = DATA_DIR / f"{split}.jsonl"

    with path.open("r", encoding="utf-8") as file:
        for line in file:
            record = json.loads(line)
            text_records[record["text"]][split].append(record["label"])

print("=" * 70)
print("VANIGUARD - CROSS-SPLIT TEXT LABEL AUDIT")
print("=" * 70)

for first, second in [
    ("train", "validation"),
    ("train", "test"),
    ("validation", "test"),
]:
    shared = [
        (text, splits)
        for text, splits in text_records.items()
        if first in splits and second in splits
    ]

    conflicting = []
    affected_examples = Counter()

    for text, splits in shared:
        first_labels = set(splits[first])
        second_labels = set(splits[second])

        if first_labels != second_labels:
            conflicting.append(text)

        affected_examples[first] += len(splits[first])
        affected_examples[second] += len(splits[second])

    print(f"\n{first.upper()} vs {second.upper()}")
    print(f"Shared exact texts: {len(shared):,}")
    print(f"Texts with different label sets: {len(conflicting):,}")
    print(f"Examples affected in {first}: {affected_examples[first]:,}")
    print(f"Examples affected in {second}: {affected_examples[second]:,}")

    if conflicting:
        print("\nSample conflicting texts:")
        for text in conflicting[:3]:
            print("-" * 50)
            print(text[:250].replace("\n", " "))
            print("Labels:", {
                first: sorted(set(text_records[text][first])),
                second: sorted(set(text_records[text][second])),
            })

print("\nAudit completed.")
