
from pathlib import Path
import joblib

MODEL_PATH = (
    Path(__file__).resolve().parents[2]
    / "models"
    / "conversation_fraud_baseline.joblib"
)


class FraudDetectionService:
    def __init__(self):
        if not MODEL_PATH.exists():
            raise FileNotFoundError(
                f"Fraud detection model not found: {MODEL_PATH}"
            )

        self.model = joblib.load(MODEL_PATH)

        classes = list(self.model.classes_)
        if 1 not in classes:
            raise ValueError(f"Expected fraud label 1, found {classes}")

        self.fraud_index = classes.index(1)

    def analyze(self, text: str) -> dict:
        cleaned_text = text.strip()

        if not cleaned_text:
            raise ValueError("Conversation text cannot be empty.")

        probabilities = self.model.predict_proba([cleaned_text])[0]
        fraud_score = float(probabilities[self.fraud_index])

        prediction = "FRAUD" if fraud_score >= 0.5 else "NORMAL"

        return {
            "prediction": prediction,
            "fraud_score": round(fraud_score * 100, 2),
            "model": "tfidf-logistic-regression-v1",
            "warning": (
                "Experimental model output; not a calibrated probability "
                "or a definitive fraud determination."
            )
        }


fraud_detector = FraudDetectionService()
	