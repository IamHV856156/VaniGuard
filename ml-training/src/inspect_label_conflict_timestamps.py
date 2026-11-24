
from datasets import load_dataset
from collections import defaultdict

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 65)
print("VANIGUARD - CONFLICT TIMESTAMP ANALYSIS")
print("=" * 65)

dataset = load_dataset(DATASET_ID)

for split_name, data in dataset.items():
    print(f"\n{'=' * 20} {split_name.upper()} {'=' * 20}")

    # Group each transcript state by its timestamps and labels.
    grouped = defaultdict(lambda: defaultdict(list))

    for uid, timestamp, verdict, text in zip(
        data["conversation_uid"],
        data["chunk_timestamp"],
        data["final_verdict"],
        data["cumulative_text"],
    ):
        grouped[(uid, text)][verdict].append(timestamp)

    conflict_count = 0
    same_timestamp_conflicts = 0
    different_timestamp_conflicts = 0
    examples = []

    for (uid, text), verdict_timestamps in grouped.items():
        if len(verdict_timestamps) < 2:
            continue

        conflict_count += 1

        no_times = verdict_timestamps.get("NO", [])
        yes_times = verdict_timestamps.get("YES", [])

        if set(no_times) & set(yes_times):
            same_timestamp_conflicts += 1
        else:
            different_timestamp_conflicts += 1

        if len(examples) < 3:
            examples.append({
                "uid": uid,
                "no_times": sorted(set(no_times)),
                "yes_times": sorted(set(yes_times)),
                "text": text[:200],
            })

    print(f"Conflicting transcript states: {conflict_count:,}")
    print(f"Same-timestamp conflicts: {same_timestamp_conflicts:,}")
    print(f"Different-timestamp conflicts: {different_timestamp_conflicts:,}")

    if examples:
        print("\nExamples:")

        for item in examples:
            print(f"\nConversation: {item['uid']}")
            print(f"NO timestamps: {item['no_times']}")
            print(f"YES timestamps: {item['yes_times']}")
            print(f"Transcript: {item['text']}")

print("\nConflict timestamp analysis completed.")
