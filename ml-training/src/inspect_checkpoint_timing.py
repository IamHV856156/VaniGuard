
from datasets import load_dataset
from collections import defaultdict
import statistics

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 65)
print("VANIGUARD - CHECKPOINT TIMING AUDIT")
print("=" * 65)

dataset = load_dataset(DATASET_ID)

for split_name, data in dataset.items():
    print(f"\n{'=' * 20} {split_name.upper()} {'=' * 20}")

    conversations = defaultdict(list)

    for uid, timestamp, verdict, text in zip(
        data["conversation_uid"],
        data["chunk_timestamp"],
        data["final_verdict"],
        data["cumulative_text"],
    ):
        conversations[uid].append((timestamp, verdict, text))

    durations = []
    chunk_counts = []
    unique_text_counts = []
    timestamp_counts = []
    verdict_transitions = 0

    for records in conversations.values():
        records.sort(key=lambda item: item[0])

        durations.append(records[-1][0] - records[0][0])
        chunk_counts.append(len(records))
        timestamp_counts.append(len(set(r[0] for r in records)))
        unique_text_counts.append(len(set(r[2] for r in records)))

        previous_verdict = records[0][1]

        for _, verdict, _ in records[1:]:
            if verdict != previous_verdict:
                verdict_transitions += 1

            previous_verdict = verdict

    print(f"Unique conversations: {len(conversations):,}")
    print(f"Total chunks: {len(data):,}")

    print("\nChunks per conversation:")
    print(f"  Median: {statistics.median(chunk_counts):.1f}")
    print(f"  Minimum: {min(chunk_counts):,}")
    print(f"  Maximum: {max(chunk_counts):,}")

    print("\nUnique timestamps per conversation:")
    print(f"  Median: {statistics.median(timestamp_counts):.1f}")

    print("\nUnique transcript versions per conversation:")
    print(f"  Median: {statistics.median(unique_text_counts):.1f}")

    print("\nConversation duration (seconds):")
    print(f"  Median: {statistics.median(durations):.1f}")
    print(f"  Minimum: {min(durations):.1f}")
    print(f"  Maximum: {max(durations):.1f}")

    print(f"\nTimestamp-level verdict transitions: {verdict_transitions:,}")

print("\nCheckpoint timing audit completed.")
