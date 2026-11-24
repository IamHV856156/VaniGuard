
import os
from groq import Groq

from app.services.local_transcription import transcribe_audio_bytes
from dotenv import load_dotenv

load_dotenv()


MODEL = os.getenv("GROQ_STT_MODEL", "whisper-large-v3-turbo")


def transcribe_audio(filename: str, audio_bytes: bytes) -> dict:
    if not audio_bytes:
        raise ValueError("Audio data cannot be empty.")

    api_key = os.getenv("GROQ_API_KEY")

    # 1. Try cloud transcription first.
    if api_key:
        try:
            client = Groq(
                api_key=api_key,
                timeout=20.0,
                max_retries=0
            )

            result = client.audio.transcriptions.create(
                file=(filename, audio_bytes),
                model=MODEL,
                response_format="text"
            )

            transcript = (
                result if isinstance(result, str) else result.text
            ).strip()

            if transcript:
                return {
                    "transcript": transcript,
                    "language": None,
                    "model": MODEL,
                    "processing": "cloud"
                }

        except Exception as error:
            print(f"Cloud transcription unavailable: {error}")

    # 2. Fall back to local transcription.
    try:
        local_result = transcribe_audio_bytes(audio_bytes)

        local_result["processing"] = "local"
        local_result["fallback"] = True

        return local_result

    except Exception as error:
        print(f"Local transcription failed: {error}")

        raise RuntimeError(
            "Both cloud and local transcription failed."
        ) from error
