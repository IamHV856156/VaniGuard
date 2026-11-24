
import json
from collections import defaultdict, Counter
from pathlib import Path

from datasets import load_dataset

DATASET_ID = "rishia2220/icfd-31k"
MANIFEST_PATH = Path("data/processed/conversation_splits.json")
OUTPUT_DIR = Path("data/processed")

print("=" * 70)
print("VANIGUARD - CONVERSATION DATA PREPARATION")
print("=" * 70)

# ---------------------------------------------------------
# 1. Load source dataset and split manifest
# ---------------------------------------------------------

if not MANIFEST_PATH.exists():
    raise FileNotFoundError(
        f"Split manifest not found: {MANIFEST_PATH}"
    )

print("\nLoading source dataset...")
dataset = load_dataset(DATASET_ID)

print("Loading conversation split manifest...")
with MANIFEST_PATH.open("r", encoding="utf-8") as file:
    manifest = json.load(file)

conversation_to_split = {}

for split_name, records in manifest["splits"].items():
    for record in records:
        uid = record["conversation_uid"]

        if uid in conversation_to_split:
            raise ValueError(
                f"Conversation appears in multiple splits: {uid}"
            )

        conversation_to_split[uid] = split_name

print(f"Mapped conversations: {len(conversation_to_split):,}")

# ---------------------------------------------------------
# 2. Collect transcript checkpoints
# ---------------------------------------------------------

# Key:
# (conversation_id, exact_transcript)
#
# Value:
# All observed timestamp/verdict pairs for that state.

transcript_states = defaultdict(list)

total_rows = 0
ignored_conversations = 0

print("\nCollecting transcript checkpoints...")

for source_split, data in dataset.items():
    for uid, timestamp, verdict, text in zip(
        data["conversation_uid"],
        data["chunk_timestamp"],
        data["final_verdict"],
        data["cumulative_text"],
    ):
        total_rows += 1

        target_split = conversation_to_split.get(uid)

        # We only use conversations assigned through our
        # newly generated manifest.
        if target_split is None:
            ignored_conversations += 1
            continue

        if target_split not in {"train", "validation", "test"}:
            continue

        if text is None or not text.strip():
            continue

        normalized_verdict = verdict.strip().upper()

        if normalized_verdict not in {"YES", "NO"}:
            raise ValueError(
                f"Unexpected verdict {verdict!r} in {uid}"
            )

        transcript_states[(uid, text)].append({
            "timestamp": float(timestamp),
            "verdict": normalized_verdict,
        })

print(f"Source rows scanned: {total_rows:,}")
print(f"Unique transcript states: {len(transcript_states):,}")

# ---------------------------------------------------------
# 3. Resolve duplicate and conflicting transcript states
# ---------------------------------------------------------

prepared = {
    "train": [],
    "validation": [],
    "test": [],
}

report = {
    "dataset": DATASET_ID,
    "label_mapping": {
        "NO": 0,
        "YES": 1,
    },
    "selection_policy": (
        "All unique, non-conflicting transcript states "
        "within each conversation."
    ),
    "conflict_policy": (
        "Exclude transcript states that have different "
        "verdicts at different timestamps."
    ),
    "splits": {},
}

duplicate_rows = Counter()
conflicting_states = Counter()
kept_states = Counter()
empty_states = Counter()
label_counts = {
    split: Counter() for split in prepared
}

print("\nCleaning transcript states...")

for (uid, text), observations in transcript_states.items():

    split_name = conversation_to_split[uid]

    # Collapse repeated copies of the same timestamp/verdict.
    unique_observations = {
        (item["timestamp"], item["verdict"])
        for item in observations
    }

    duplicate_rows[split_name] += (
        len(observations) - len(unique_observations)
    )

    # Determine whether the exact same transcript has
    # contradictory labels at different timestamps.
    observed_verdicts = {
        verdict for _, verdict in unique_observations
    }

    if len(observed_verdicts) > 1:
        conflicting_states[split_name] += 1
        continue

    # All remaining observations have one consistent label.
    # Keep the earliest timestamp for this transcript state.
    # This avoids counting identical cumulative text repeatedly.
    earliest_timestamp = min(
        timestamp for timestamp, _ in unique_observations
    )

    verdict = next(iter(observed_verdicts))
    label = 1 if verdict == "YES" else 0

    if not text.strip():
        empty_states[split_name] += 1
        continue

    prepared[split_name].append({
        "conversation_id": uid,
        "timestamp": earliest_timestamp,
        "text": text.strip(),
        "label": label,
    })

    kept_states[split_name] += 1
    label_counts[split_name][verdict] += 1

# ---------------------------------------------------------
# 4. Sort examples chronologically
# ---------------------------------------------------------

for split_name in prepared:
    prepared[split_name].sort(
        key=lambda item: (
            item["conversation_id"],
            item["timestamp"],
        )
    )

# ---------------------------------------------------------
# 5. Save JSONL files
# ---------------------------------------------------------

OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

for split_name, records in prepared.items():
    output_file = OUTPUT_DIR / f"{split_name}.jsonl"

    with output_file.open("w", encoding="utf-8") as file:
        for record in records:
            file.write(
                json.dumps(record, ensure_ascii=False) + "\n"
            )

    print(
        f"\nSaved {split_name}: "
        f"{len(records):,} examples"
    )

# ---------------------------------------------------------
# 6. Build cleaning report
# ---------------------------------------------------------

for split_name in prepared:
    report["splits"][split_name] = {
        "examples_kept": len(prepared[split_name]),
        "duplicate_rows_collapsed": duplicate_rows[split_name],
        "conflicting_states_excluded": conflicting_states[split_name],
        "empty_states_excluded": empty_states[split_name],
        "label_distribution": {
            "NO": label_counts[split_name]["NO"],
            "YES": label_counts[split_name]["YES"],
        },
    }

report["total_source_rows_scanned"] = total_rows
report["conversations_not_in_manifest"] = ignored_conversations

report_file = OUTPUT_DIR / "cleaning_report.json"

with report_file.open("w", encoding="utf-8") as file:
    json.dump(report, file, ensure_ascii=False, indent=2)

# ---------------------------------------------------------
# 7. Final summary
# ---------------------------------------------------------

print("\n" + "=" * 70)
print("DATA PREPARATION SUMMARY")
print("=" * 70)

for split_name, records in prepared.items():
    print(f"\n{split_name.upper()}")
    print(f"  Examples kept: {len(records):,}")
    print(
        f"  Duplicate rows collapsed: "
        f"{duplicate_rows[split_name]:,}"
    )
    print(
        f"  Conflicting states excluded: "
        f"{conflicting_states[split_name]:,}"
    )
    print(
        f"  NO labels: {label_counts[split_name]['NO']:,}"
    )
    print(
        f"  YES labels: {label_counts[split_name]['YES']:,}"
    )

print("\nOutput directory:", OUTPUT_DIR.resolve())
print("Cleaning report:", report_file.resolve())
print("\nPreparation completed.")
