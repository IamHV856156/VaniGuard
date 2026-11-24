
from datasets import load_dataset
from collections import defaultdict

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 65)
print("VANIGUARD - TRANSCRIPT LABEL CONSISTENCY AUDIT")
print("=" * 65)

dataset = load_dataset(DATASET_ID)

for split_name, data in dataset.items():
    print(f"\n{'=' * 20} {split_name.upper()} {'=' * 20}")

    # conversation_uid -> transcript -> set of verdicts
    transcript_labels = defaultdict(lambda: defaultdict(set))

    total_rows = 0

    for uid, text, verdict in zip(
        data["conversation_uid"],
        data["cumulative_text"],
        data["final_verdict"],
    ):
        total_rows += 1
        transcript_labels[uid][text].add(verdict)

    unique_transcripts = 0
    conflicting_transcripts = 0
    conflicting_conversations = 0
    examples = []

    for uid, transcripts in transcript_labels.items():
        conversation_has_conflict = False

        for text, verdicts in transcripts.items():
            unique_transcripts += 1

            if len(verdicts) > 1:
                conflicting_transcripts += 1
                conversation_has_conflict = True

                if len(examples) < 3:
                    examples.append({
                        "conversation": uid,
                        "verdicts": sorted(verdicts),
                        "text": text[:250],
                    })

        if conversation_has_conflict:
            conflicting_conversations += 1

    print(f"Total rows: {total_rows:,}")
    print(f"Unique transcript states: {unique_transcripts:,}")
    print(f"Identical transcripts with conflicting labels: {conflicting_transcripts:,}")
    print(f"Conversations containing conflicts: {conflicting_conversations:,}")

    if examples:
        print("\nConflict examples:")

        for example in examples:
            print(f"\nConversation: {example['conversation']}")
            print(f"Verdicts: {example['verdicts']}")
            print(f"Transcript: {example['text']}")

    else:
        print("\nNo identical-transcript label conflicts found.")

print("\nConsistency audit completed.")
