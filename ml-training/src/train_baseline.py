
import json
from pathlib import Path

import joblib
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    classification_report,
    confusion_matrix,
    roc_auc_score,
)
from sklearn.pipeline import Pipeline

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "data" / "processed"
MODEL_DIR = ROOT / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)


def load_jsonl(path):
    texts = []
    labels = []

    print(f"Loading {path.name}...")

    with path.open("r", encoding="utf-8") as file:
        for line in file:
            row = json.loads(line)
            texts.append(row["text"])
            labels.append(int(row["label"]))

    print(f"Loaded {len(texts):,} examples.")
    return texts, np.array(labels)


X_train, y_train = load_jsonl(DATA_DIR / "train_final.jsonl")
X_val, y_val = load_jsonl(DATA_DIR / "validation_final.jsonl")
X_test, y_test = load_jsonl(DATA_DIR / "test_final.jsonl")

print("\nTraining TF-IDF + Logistic Regression...")

model = Pipeline([
    (
        "tfidf",
        TfidfVectorizer(
            lowercase=True,
            strip_accents=None,
            analyzer="char",
            ngram_range=(2, 5),
            min_df=2,
            max_features=300_000,
            sublinear_tf=True,
            dtype=np.float32,
        ),
    ),
    (
        "classifier",
        LogisticRegression(
            C=2.0,
            class_weight="balanced",
            max_iter=1000,
            solver="liblinear",
            random_state=42,
        ),
    ),
])

model.fit(X_train, y_train)

def evaluate(name, texts, labels):
    predictions = model.predict(texts)
    probabilities = model.predict_proba(texts)[:, 1]

    print(f"\n{'=' * 65}")
    print(f"{name.upper()} RESULTS")
    print("=" * 65)

    print("\nClassification report:")
    print(
        classification_report(
            labels,
            predictions,
            target_names=["NORMAL", "FRAUD"],
            digits=4,
        )
    )

    print("Confusion matrix:")
    print(confusion_matrix(labels, predictions))

    print(f"ROC-AUC: {roc_auc_score(labels, probabilities):.4f}")


evaluate("Validation", X_val, y_val)
evaluate("Test", X_test, y_test)

model_path = MODEL_DIR / "conversation_fraud_baseline.joblib"
joblib.dump(model, model_path, compress=3)

print("\nModel saved successfully:")
print(model_path)
print("\nBaseline training completed.")
