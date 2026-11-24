
from collections import Counter, defaultdict
from datasets import load_dataset
import re

DATASET_ID = "sidzzz07/scamshield-dataset"

dataset = load_dataset(DATASET_ID)

def script_type(text):
    devanagari = len(re.findall(r"[\u0900-\u097F]", text))
    latin = len(re.findall(r"[A-Za-z]", text))

    if devanagari and latin:
        return "MIXED"
    if devanagari:
        return "DEVANAGARI"
    if latin:
        return "LATIN"
    return "OTHER"

print("=" * 70)
print("VANIGUARD - SCAMSHIELD QUALITY AUDIT")
print("=" * 70)

group_splits = defaultdict(set)

for split_name, data in dataset.items():
    print(f"\n{'=' * 25} {split_name.upper()} {'=' * 25}")

    languages = Counter()
    scripts = Counter()
    sources = Counter()
    labels = Counter()
    intents = Counter()
    tactic_counts = Counter()
    metadata_counts = Counter()
    intent_mismatches = []

    hindi_samples = []
    hinglish_samples = []
    suspicious_benign_samples = []

    for row in data:
        text = row["text"]
        label = int(row["is_scam"])
        language = str(row["language"])
        source = str(row["source_dataset"])
        intent = str(row["head2_scam_intent"])

        languages[language] += 1
        scripts[(language, script_type(text))] += 1
        sources[source] += 1
        labels[label] += 1
        intents[(label, intent)] += 1

        group_splits[str(row["split_group"])].add(split_name)

        tactics = row.get("head1_social_engineering") or {}
        for tactic, value in tactics.items():
            if value:
                tactic_counts[tactic] += 1

        metadata = row.get("metadata") or {}
        for key, value in metadata.items():
            if value:
                metadata_counts[(label, key)] += 1

        expected_scam_intent = intent != "Legitimate / Benign"
        if expected_scam_intent != bool(label):
            if len(intent_mismatches) < 8:
                intent_mismatches.append({
                    "text": text[:180],
                    "is_scam": label,
                    "intent": intent
                })

        if language == "Hindi" and len(hindi_samples) < 3:
            hindi_samples.append(text[:200])

        if language == "Hinglish" and len(hinglish_samples) < 3:
            hinglish_samples.append(text[:200])

        has_link = bool(metadata.get("has_url"))
        has_otp = bool(metadata.get("has_otp"))
        has_upi = bool(metadata.get("has_upi"))

        if label == 0 and (has_link or has_otp or has_upi):
            if len(suspicious_benign_samples) < 8:
                suspicious_benign_samples.append({
                    "text": text[:180],
                    "metadata": metadata,
                    "intent": intent
                })

    print("\nLanguage distribution:")
    for key, value in languages.most_common():
        print(f"  {key}: {value:,}")

    print("\nLanguage and script distribution:")
    for key, value in scripts.most_common():
        print(f"  {key}: {value:,}")

    print("\nSource datasets:")
    for key, value in sources.most_common():
        print(f"  {key}: {value:,}")

    print("\nLabels:")
    print(f"  Benign: {labels[0]:,}")
    print(f"  Scam:   {labels[1]:,}")

    print("\nIntent versus scam label:")
    for key, value in intents.most_common():
        print(f"  label={key}, intent={key[1]}: {value:,}")

    print(f"\nIntent/label mismatches (sample): {len(intent_mismatches)}")
    for item in intent_mismatches:
        print(" ", item)

    print("\nPositive social-engineering tactic counts:")
    for key, value in tactic_counts.most_common():
        print(f"  {key}: {value:,}")

    print("\nMetadata indicators by label:")
    for (label, key), value in sorted(metadata_counts.items()):
        print(f"  label={label}, {key}: {value:,}")

    print("\nHindi samples:")
    for sample in hindi_samples:
        print(" ", sample)

    print("\nHinglish samples:")
    for sample in hinglish_samples:
        print(" ", sample)

    print("\nBenign examples containing URL/OTP/UPI indicators:")
    for item in suspicious_benign_samples:
        print(" ", item)

print("\n" + "=" * 70)
print("SPLIT GROUP LEAKAGE CHECK")
print("=" * 70)

leaked_groups = {
    group: sorted(splits)
    for group, splits in group_splits.items()
    if len(splits) > 1
}

print(f"Unique split groups: {len(group_splits):,}")
print(f"Groups appearing in multiple splits: {len(leaked_groups):,}")

for group, splits in list(leaked_groups.items())[:20]:
    print(f"  {group}: {splits}")

print("\nAudit completed.")
