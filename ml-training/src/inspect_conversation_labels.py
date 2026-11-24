
from datasets import load_dataset
from collections import Counter, defaultdict

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 70)
print("VANIGUARD - CONVERSATION-LEVEL LABEL AUDIT")
print("=" * 70)

dataset = load_dataset(DATASET_ID)

for split_name, data in dataset.items():
    print(f"\n{'=' * 25} {split_name.upper()} {'=' * 25}")

    conversations = {}

    for uid, timestamp, verdict, case_type, domain in zip(
        data["conversation_uid"],
        data["chunk_timestamp"],
        data["final_verdict"],
        data["case_type"],
        data["domain"],
    ):
        existing = conversations.get(uid)

        if existing is None or timestamp >= existing["timestamp"]:
            conversations[uid] = {
                "timestamp": timestamp,
                "verdict": verdict,
                "case_type": case_type,
                "domain": domain,
            }

    print(f"Unique conversations: {len(conversations):,}")

    print("\nConversation-level final verdict distribution:")
    for label, count in Counter(
        item["verdict"] for item in conversations.values()
    ).most_common():
        print(f"  {label}: {count:,}")

    print("\nConversation-level case type distribution:")
    for label, count in Counter(
        item["case_type"] for item in conversations.values()
    ).most_common():
        print(f"  {label}: {count:,}")

    print("\nConversation-level domain distribution:")
    for label, count in Counter(
        item["domain"] for item in conversations.values()
    ).most_common():
        print(f"  {label}: {count:,}")

print("\n" + "=" * 70)
print("Conversation-level audit completed.")
print("=" * 70)
