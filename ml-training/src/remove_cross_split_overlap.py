
import json
from pathlib import Path

DATA_DIR = Path("data/processed")

FILES = {
    "train": DATA_DIR / "train.jsonl",
    "validation": DATA_DIR / "validation.jsonl",
    "test": DATA_DIR / "test.jsonl",
}


def load_records(path):
    records = []

    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if line.strip():
                records.append(json.loads(line))

    return records


def save_records(path, records):
    with path.open("w", encoding="utf-8") as file:
        for record in records:
            file.write(
                json.dumps(record, ensure_ascii=False) + "\n"
            )


print("=" * 70)
print("VANIGUARD - CROSS-SPLIT OVERLAP REMOVAL")
print("=" * 70)

# Load original datasets
train = load_records(FILES["train"])
validation = load_records(FILES["validation"])
test = load_records(FILES["test"])

print(f"\nOriginal train:      {len(train):,}")
print(f"Original validation: {len(validation):,}")
print(f"Original test:       {len(test):,}")

# Exact text already present in training
train_texts = {record["text"] for record in train}

# Validation must not contain training text
validation_final = [
    record for record in validation
    if record["text"] not in train_texts
]

validation_texts = {
    record["text"] for record in validation_final
}

# Test must not contain training or retained validation text
blocked_test_texts = train_texts | validation_texts

test_final = [
    record for record in test
    if record["text"] not in blocked_test_texts
]

# Final train set is unchanged
train_final = train

# Save separate final files. Original files remain untouched.
save_records(DATA_DIR / "train_final.jsonl", train_final)
save_records(DATA_DIR / "validation_final.jsonl", validation_final)
save_records(DATA_DIR / "test_final.jsonl", test_final)

# Verify no exact text overlap remains
final_texts = {
    "train": {r["text"] for r in train_final},
    "validation": {r["text"] for r in validation_final},
    "test": {r["text"] for r in test_final},
}

print("\n" + "=" * 70)
print("FINAL DATASET SUMMARY")
print("=" * 70)

for name, records, original in [
    ("TRAIN", train_final, train),
    ("VALIDATION", validation_final, validation),
    ("TEST", test_final, test),
]:
    removed = len(original) - len(records)
    labels = {
        0: sum(r["label"] == 0 for r in records),
        1: sum(r["label"] == 1 for r in records),
    }

    print(f"\n{name}")
    print(f"  Final examples: {len(records):,}")
    print(f"  Removed examples: {removed:,}")
    print(f"  Normal: {labels[0]:,}")
    print(f"  Fraud: {labels[1]:,}")

print("\n" + "=" * 70)
print("FINAL OVERLAP VERIFICATION")
print("=" * 70)

pairs = [
    ("train", "validation"),
    ("train", "test"),
    ("validation", "test"),
]

all_clear = True

for first, second in pairs:
    overlap = final_texts[first] & final_texts[second]
    print(f"{first} vs {second}: {len(overlap):,} shared texts")

    if overlap:
        all_clear = False

if not all_clear:
    raise RuntimeError("Cross-split text overlap still exists.")

print("\nAll cross-split exact-text checks passed.")

report = {
    "method": "Exact-text deduplication across splits",
    "policy": (
        "Training takes priority, validation takes priority "
        "over test. Original files remain unchanged."
    ),
    "original_counts": {
        "train": len(train),
        "validation": len(validation),
        "test": len(test),
    },
    "final_counts": {
        "train": len(train_final),
        "validation": len(validation_final),
        "test": len(test_final),
    },
    "removed_counts": {
        "train": 0,
        "validation": len(validation) - len(validation_final),
        "test": len(test) - len(test_final),
    },
    "verified_cross_split_overlap": {
        f"{first}_vs_{second}": len(
            final_texts[first] & final_texts[second]
        )
        for first, second in pairs
    },
}

with (DATA_DIR / "overlap_removal_report.json").open(
    "w", encoding="utf-8"
) as file:
    json.dump(report, file, indent=2)

print("\nFinal files saved:")
print("  train_final.jsonl")
print("  validation_final.jsonl")
print("  test_final.jsonl")
print("  overlap_removal_report.json")
print("\nOverlap removal completed successfully.")
