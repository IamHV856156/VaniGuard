
from datasets import load_dataset

DATASET_ID = "rishia2220/icfd-31k"

print("=" * 60)
print("VANIGUARD - CONVERSATION DATASET INSPECTION")
print("=" * 60)

print(f"\nLoading dataset: {DATASET_ID}")

dataset = load_dataset(DATASET_ID)

print("\nAvailable splits:")
for split_name, split_data in dataset.items():
    print(f"\nSplit: {split_name}")
    print(f"Records: {len(split_data)}")
    print(f"Columns: {split_data.column_names}")

    if len(split_data) > 0:
        print("\nFirst record:")
        print(split_data[0])

        print("\nSecond record:")
        if len(split_data) > 1:
            print(split_data[1])

print("\nInspection completed.")
