
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

DATA_DIR = Path("data/processed")
SPLITS = ["train", "validation", "test"]
SAMPLE_SIZE = 5
SEED = 42

random.seed(SEED)


def classify_script(text):
    devanagari = 0
    latin = 0
    other_letters = 0

    for char in text:
        if "\u0900" <= char <= "\u097F":
            devanagari += 1
        elif ("a" <= char.lower() <= "z"):
            latin += 1
        elif char.isalpha():
            other_letters += 1

    total = devanagari + latin + other_letters

    if total == 0:
        return "NO_LETTERS"

    if devanagari > 0 and latin > 0:
        return "MIXED_DEVANAGARI_LATIN"

    if devanagari > 0:
        return "DEVANAGARI"

    if latin > 0:
        return "LATIN_ONLY"

    return "OTHER_SCRIPT"


print("=" * 70)
print("VANIGUARD - LANGUAGE AND SCRIPT COMPOSITION AUDIT")
print("=" * 70)

for split in SPLITS:
    path = DATA_DIR / f"{split}_final.jsonl"

    if not path.exists():
        raise FileNotFoundError(path)

    counts = Counter()
    samples = defaultdict(list)
    label_counts = defaultdict(Counter)

    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue

            record = json.loads(line)
            text = record["text"]

            category = classify_script(text)
            label = record["label"]

            counts[category] += 1
            label_counts[category][label] += 1

            # Keep a small random sample for manual inspection.
            bucket = samples[(category, label)]

            if len(bucket) < SAMPLE_SIZE:
                bucket.append(text)
            else:
                index = random.randrange(counts[category])
                if index < SAMPLE_SIZE:
                    bucket[index] = text

    total = sum(counts.values())

    print(f"\n{'=' * 70}")
    print(split.upper())
    print(f"{'=' * 70}")

    for category, count in counts.most_common():
        percentage = count / total * 100

        print(f"\n{category}")
        print(f"  Examples: {count:,} ({percentage:.2f}%)")
        print(f"  Normal: {label_counts[category][0]:,}")
        print(f"  Fraud:  {label_counts[category][1]:,}")

    print("\nSAMPLE TRANSCRIPTS")
    print("-" * 70)

    for category in [
        "LATIN_ONLY",
        "DEVANAGARI",
        "MIXED_DEVANAGARI_LATIN",
    ]:
        for label in [0, 1]:
            bucket = samples.get((category, label), [])

            if not bucket:
                continue

            print(
                f"\n{category} | "
                f"{'FRAUD' if label == 1 else 'NORMAL'}"
            )

            for index, text in enumerate(bucket[:3], start=1):
                preview = text[:450].replace("\n", " ")
                print(f"{index}. {preview}")

print("\nLanguage/script audit completed.")
