
from datasets import load_dataset
from collections import Counter

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 65)
print("VANIGUARD - DATASET QUALITY AUDIT")
print("=" * 65)

dataset = load_dataset(DATASET_ID)

split_conversations = {}
split_rows = {}

for split_name, data in dataset.items():
    print(f"\n{'=' * 25} {split_name.upper()} {'=' * 25}")

    conversation_ids = data["conversation_uid"]
    verdicts = data["final_verdict"]
    domains = data["domain"]
    case_types = data["case_type"]

    unique_ids = set(conversation_ids)

    split_conversations[split_name] = unique_ids
    split_rows[split_name] = len(data)

    print(f"Total chunks: {len(data):,}")
    print(f"Unique conversations: {len(unique_ids):,}")
    print(f"Repeated chunks beyond first per conversation: "
          f"{len(data) - len(unique_ids):,}")

    print("\nVerdict distribution:")
    for label, count in Counter(verdicts).most_common():
        print(f"  {label}: {count:,}")

    print("\nDomain distribution:")
    for label, count in Counter(domains).most_common():
        print(f"  {label}: {count:,}")

    print("\nCase type distribution:")
    for label, count in Counter(case_types).most_common():
        print(f"  {label}: {count:,}")

print("\n" + "=" * 65)
print("CROSS-SPLIT LEAKAGE CHECK")
print("=" * 65)

split_names = list(split_conversations.keys())

for i in range(len(split_names)):
    for j in range(i + 1, len(split_names)):
        first = split_names[i]
        second = split_names[j]

        overlap = (
            split_conversations[first]
            & split_conversations[second]
        )

        print(f"\n{first} vs {second}")
        print(f"Shared conversation IDs: {len(overlap):,}")

        if overlap:
            print("WARNING: Conversation leakage detected.")
            print("Example shared IDs:")
            for conversation_id in list(overlap)[:5]:
                print(f"  {conversation_id}")
        else:
            print("No shared conversation IDs detected.")

print("\nAudit completed.")
