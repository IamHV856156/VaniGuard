
import json
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median

DATA_DIR = Path("data/processed")
SPLITS = ["train", "validation", "test"]


def percentile(values, fraction):
    if not values:
        return 0

    ordered = sorted(values)
    index = round((len(ordered) - 1) * fraction)
    return ordered[index]


all_conversation_ids = {}
all_texts_by_split = {}
total_examples = 0

print("=" * 70)
print("VANIGUARD - PREPARED DATASET AUDIT")
print("=" * 70)

for split in SPLITS:
    file_path = DATA_DIR / f"{split}.jsonl"

    if not file_path.exists():
        raise FileNotFoundError(file_path)

    records = []

    with file_path.open("r", encoding="utf-8") as file:
        for line in file:
            if line.strip():
                records.append(json.loads(line))

    conversation_counts = Counter(
        record["conversation_id"] for record in records
    )

    labels = Counter(record["label"] for record in records)

    text_lengths = [
        len(record["text"]) for record in records
    ]

    unique_texts = {
        record["text"] for record in records
    }

    all_conversation_ids[split] = set(conversation_counts)
    all_texts_by_split[split] = unique_texts
    total_examples += len(records)

    examples_per_conversation = list(
        conversation_counts.values()
    )

    print(f"\n{'=' * 60}")
    print(split.upper())
    print(f"{'=' * 60}")

    print(f"Total examples: {len(records):,}")
    print(f"Unique conversations: {len(conversation_counts):,}")

    print("\nLabel distribution:")
    print(f"  Normal (0): {labels[0]:,}")
    print(f"  Fraud  (1): {labels[1]:,}")

    fraud_percentage = (
        labels[1] / len(records) * 100 if records else 0
    )

    print(f"  Fraud percentage: {fraud_percentage:.2f}%")

    print("\nTranscript length (characters):")
    print(f"  Minimum: {min(text_lengths):,}")
    print(f"  Median: {median(text_lengths):,.0f}")
    print(f"  90th percentile: {percentile(text_lengths, 0.90):,}")
    print(f"  Maximum: {max(text_lengths):,}")

    print("\nExamples per conversation:")
    print(f"  Minimum: {min(examples_per_conversation):,}")
    print(f"  Median: {median(examples_per_conversation):,.0f}")
    print(f"  90th percentile: {percentile(examples_per_conversation, 0.90):,}")
    print(f"  Maximum: {max(examples_per_conversation):,}")

    print(f"\nUnique transcript texts: {len(unique_texts):,}")
    print(
        "Repeated text examples within split: "
        f"{len(records) - len(unique_texts):,}"
    )

# ---------------------------------------------------------
# Check conversation leakage
# ---------------------------------------------------------

print(f"\n{'=' * 70}")
print("CONVERSATION SPLIT ISOLATION")
print(f"{'=' * 70}")

for i, first in enumerate(SPLITS):
    for second in SPLITS[i + 1:]:
        overlap = (
            all_conversation_ids[first]
            & all_conversation_ids[second]
        )

        print(
            f"{first} vs {second}: "
            f"{len(overlap)} shared conversations"
        )

# ---------------------------------------------------------
# Check exact transcript text across splits
# ---------------------------------------------------------

print(f"\n{'=' * 70}")
print("EXACT TEXT OVERLAP BETWEEN SPLITS")
print(f"{'=' * 70}")

for i, first in enumerate(SPLITS):
    for second in SPLITS[i + 1:]:
        overlap = (
            all_texts_by_split[first]
            & all_texts_by_split[second]
        )

        print(
            f"{first} vs {second}: "
            f"{len(overlap):,} shared exact texts"
        )

print(f"\nTotal prepared examples: {total_examples:,}")
print("\nAudit completed.")
