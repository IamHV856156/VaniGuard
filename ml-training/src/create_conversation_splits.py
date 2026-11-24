
import json
from pathlib import Path

from datasets import load_dataset
from sklearn.model_selection import train_test_split

DATASET_ID = "rishia2220/icfd-31k"
RANDOM_STATE = 42

print("=" * 65)
print("VANIGUARD - GROUPED DATASET SPLIT GENERATOR")
print("=" * 65)

dataset = load_dataset(DATASET_ID)
train_data = dataset["train"]

print("\nReading conversation-level labels...")

# Find the final timestamp-level verdict for each conversation.
conversation_labels = {}
conversation_timestamps = {}

for uid, timestamp, verdict in zip(
    train_data["conversation_uid"],
    train_data["chunk_timestamp"],
    train_data["final_verdict"],
):
    previous_timestamp = conversation_timestamps.get(uid)

    if previous_timestamp is None or timestamp > previous_timestamp:
        conversation_timestamps[uid] = timestamp
        conversation_labels[uid] = verdict

    elif timestamp == previous_timestamp:
        previous_verdict = conversation_labels[uid]

        if previous_verdict != verdict:
            raise ValueError(
                f"Conflicting final verdicts for conversation: {uid}"
            )

conversation_ids = list(conversation_labels.keys())
labels = [conversation_labels[uid] for uid in conversation_ids]

print(f"Unique conversations: {len(conversation_ids):,}")

print("\nOriginal conversation-level labels:")
for label in sorted(set(labels)):
    print(f"  {label}: {labels.count(label):,}")

# First split: 80% train, 20% temporary.
train_ids, temporary_ids, train_labels, temporary_labels = (
    train_test_split(
        conversation_ids,
        labels,
        test_size=0.20,
        random_state=RANDOM_STATE,
        stratify=labels,
    )
)

# Second split: divide temporary set equally into validation and test.
validation_ids, test_ids, _, _ = train_test_split(
    temporary_ids,
    temporary_labels,
    test_size=0.50,
    random_state=RANDOM_STATE,
    stratify=temporary_labels,
)

splits = {
    "train": train_ids,
    "validation": validation_ids,
    "test": test_ids,
}

# Verify that no conversation appears in multiple splits.
assert set(train_ids).isdisjoint(validation_ids)
assert set(train_ids).isdisjoint(test_ids)
assert set(validation_ids).isdisjoint(test_ids)

# Save only conversation IDs and their final labels.
# We will use this manifest to assign all chunks later.
output_dir = Path("data/processed")
output_dir.mkdir(parents=True, exist_ok=True)

manifest = {
    "dataset": DATASET_ID,
    "random_state": RANDOM_STATE,
    "split_method": "stratified conversation-level split",
    "label_definition": "final_verdict at maximum chunk_timestamp",
    "splits": {},
}

for split_name, ids in splits.items():
    manifest["splits"][split_name] = [
        {
            "conversation_uid": uid,
            "final_verdict": conversation_labels[uid],
        }
        for uid in ids
    ]

    split_counts = {
        label: sum(
            conversation_labels[uid] == label
            for uid in ids
        )
        for label in sorted(set(labels))
    }

    print(f"\n{split_name.upper()}")
    print(f"  Conversations: {len(ids):,}")

    for label, count in split_counts.items():
        print(f"  {label}: {count:,}")

output_file = output_dir / "conversation_splits.json"

with output_file.open("w", encoding="utf-8") as file:
    json.dump(manifest, file, ensure_ascii=False, indent=2)

print("\n" + "=" * 65)
print("SPLIT GENERATION SUCCESSFUL")
print("=" * 65)
print(f"Manifest saved to: {output_file}")
print("Conversation overlap: 0")
