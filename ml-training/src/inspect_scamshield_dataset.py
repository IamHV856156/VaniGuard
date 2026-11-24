
from collections import Counter
from datasets import load_dataset
import json

DATASET_ID = "sidzzz07/scamshield-dataset"

print("=" * 70)
print("VANIGUARD - SCAMSHIELD DATASET INSPECTION")
print("=" * 70)

dataset = load_dataset(DATASET_ID)

print("\nAvailable splits:")

for split_name, data in dataset.items():
    print(f"\n{split_name.upper()}")
    print(f"Records: {len(data):,}")
    print(f"Columns: {data.column_names}")

    label_counts = Counter()
    language_counts = Counter()
    unique_texts = set()
    empty_texts = 0

    for record in data:
        text = record.get("text", "")
        if not isinstance(text, str) or not text.strip():
            empty_texts += 1
            continue

        unique_texts.add(text.strip())

        if "is_scam" in record:
            label_counts[str(record["is_scam"])] += 1

        if "language" in record:
            language_counts[str(record["language"])] += 1

    print(f"Unique texts: {len(unique_texts):,}")
    print(f"Repeated text rows: {len(data) - empty_texts - len(unique_texts):,}")
    print(f"Empty texts: {empty_texts:,}")
    print(f"Labels: {dict(label_counts)}")
    print(f"Languages: {dict(language_counts)}")

    print("\nSample records:")

    for index in range(min(5, len(data))):
        print(json.dumps(
            data[index],
            ensure_ascii=False,
            indent=2,
            default=str
        ))
        print("-" * 50)

print("\nInspection completed.")
