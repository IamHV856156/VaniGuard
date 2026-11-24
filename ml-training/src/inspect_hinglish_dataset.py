
import json
from collections import Counter, defaultdict
from statistics import median
from pathlib import Path

from datasets import load_dataset

DATASET_ID = "ysangam/Indian_Cyber_Scam_PhoneCall_Hinglish_Dataset"

print("=" * 70)
print("VANIGUARD - ADDITIONAL HINGLISH DATASET INSPECTION")
print("=" * 70)

print("\nLoading dataset...")
dataset = load_dataset(DATASET_ID)

if "train" not in dataset:
    raise ValueError("Expected a train split was not found.")

data = dataset["train"]

print("\nDataset information")
print(f"Dataset: {DATASET_ID}")
print(f"Records: {len(data):,}")
print(f"Columns: {data.column_names}")

labels = Counter()
categories = Counter()
caller_types = Counter()
language_styles = Counter()
text_lengths = []
text_labels = defaultdict(set)
empty_texts = 0

for record in data:
    text = record.get("text")

    if not isinstance(text, str) or not text.strip():
        empty_texts += 1
        continue

    label = int(record["label"])

    if label not in (0, 1):
        raise ValueError(f"Unexpected label: {label}")

    labels[label] += 1
    categories[str(record.get("scam_category"))] += 1
    caller_types[str(record.get("caller_type"))] += 1
    language_styles[str(record.get("language_style"))] += 1

    text_lengths.append(len(text))
    text_labels[text.strip()].add(label)

unique_texts = len(text_labels)
duplicate_texts = len(data) - empty_texts - unique_texts

conflicting_texts = {
    text: values
    for text, values in text_labels.items()
    if len(values) > 1
}

print("\n" + "=" * 70)
print("QUALITY SUMMARY")
print("=" * 70)

print(f"Total rows: {len(data):,}")
print(f"Empty texts: {empty_texts:,}")
print(f"Unique exact texts: {unique_texts:,}")
print(f"Repeated text rows: {duplicate_texts:,}")
print(f"Texts with conflicting labels: {len(conflicting_texts):,}")

print("\nLabel distribution")
for label, count in sorted(labels.items()):
    name = "FRAUD" if label == 1 else "NORMAL"
    print(f"  {name}: {count:,} ({count / len(data) * 100:.2f}%)")

print("\nText length")
print(f"  Minimum: {min(text_lengths):,}")
print(f"  Median: {median(text_lengths):,.0f}")
print(f"  Maximum: {max(text_lengths):,}")

print("\nScam categories")
for name, count in categories.most_common():
    print(f"  {name}: {count:,}")

print("\nCaller types")
for name, count in caller_types.most_common():
    print(f"  {name}: {count:,}")

print("\nLanguage styles")
for name, count in language_styles.most_common():
    print(f"  {name}: {count:,}")

print("\n" + "=" * 70)
print("SAMPLE RECORDS")
print("=" * 70)

for label in (0, 1):
    print(f"\n{'FRAUD' if label == 1 else 'NORMAL'} SAMPLES")

    count = 0

    for record in data:
        if int(record["label"]) != label:
            continue

        print(json.dumps(record, ensure_ascii=False, indent=2))
        print("-" * 50)

        count += 1
        if count == 5:
            break

if conflicting_texts:
    print("\nCONFLICTING LABEL EXAMPLES")

    for text, values in list(conflicting_texts.items())[:5]:
        print(f"Text: {text[:250]}")
        print(f"Labels: {sorted(values)}")
        print("-" * 50)

print("\nInspection completed.")
