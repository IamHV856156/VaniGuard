from faster_whisper import WhisperModel
from functools import lru_cache
from pathlib import Path
import tempfile
import os


MODEL_SIZE = "tiny"
DEVICE = "cpu"
COMPUTE_TYPE = "int8"


@lru_cache(maxsize=1)
def get_model():
    print(f"Loading local Whisper model: {MODEL_SIZE}")

    return WhisperModel(
        MODEL_SIZE,
        device=DEVICE,
        compute_type=COMPUTE_TYPE
    )


def transcribe_audio_bytes(audio_bytes: bytes, language: str | None = None) -> dict:
    if not audio_bytes:
        raise ValueError("Audio data cannot be empty.")

    temp_path = None

    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp:
            temp.write(audio_bytes)
            temp_path = temp.name

        model = get_model()

        segments, info = model.transcribe(
            temp_path,
            language=language,
            beam_size=1,
            vad_filter=True
        )

        transcript = " ".join(
            segment.text.strip()
            for segment in segments
            if segment.text.strip()
        ).strip()

        return {
            "transcript": transcript,
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "model": f"faster-whisper-{MODEL_SIZE}",
            "processing": "local"
        }

    finally:
        if temp_path and Path(temp_path).exists():
            os.remove(temp_path)