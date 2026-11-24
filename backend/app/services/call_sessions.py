import shutil
import tempfile
import threading
import uuid

from datetime import datetime, timezone
from pathlib import Path

from app.transcription import transcribe_audio
from app.services.fraud_detection import fraud_detector
from app.scam_detector import detect_scam


class CallSession:
    def __init__(self):
        self.session_id = str(uuid.uuid4())
        self.created_at = datetime.now(timezone.utc).isoformat()
        self.transcript_parts = []
        self.segment_count = 0
        self.audio_dir = Path(tempfile.mkdtemp(prefix="vaniguard_call_"))
        self.lock = threading.RLock()
        self.finished = False

    @property
    def transcript(self):
        return " ".join(self.transcript_parts).strip()


_sessions = {}
_sessions_lock = threading.RLock()


def create_session():
    session = CallSession()

    with _sessions_lock:
        _sessions[session.session_id] = session

    return {
        "session_id": session.session_id,
        "status": "ACTIVE",
        "created_at": session.created_at
    }


def get_session(session_id):
    with _sessions_lock:
        session = _sessions.get(session_id)

    if session is None:
        raise ValueError("Call session not found.")

    return session


def analyze_transcript(transcript):
    if not transcript.strip():
        return {
            "prediction": "UNKNOWN",
            "fraud_score": 0,
            "risk_level": "UNKNOWN",
            "semantic_score": None,
            "indicators": [],
            "explanation": "Not enough speech has been transcribed.",
            "warning": "No reliable assessment is available yet."
        }

    model_a = fraud_detector.analyze(transcript)

    model_b = None
    try:
        model_b = detect_scam(transcript)
    except Exception as error:
        print(f"Semantic analysis unavailable: {error}")

    model_a_score = float(model_a["fraud_score"])
    semantic_score = (
        float(model_b["semantic_score"])
        if model_b else None
    )

    final_score = max(
        model_a_score,
        semantic_score if semantic_score is not None else 0
    )

    if final_score >= 70:
        prediction = "FRAUD"
        risk_level = "HIGH"
    elif final_score >= 40:
        prediction = "SUSPICIOUS"
        risk_level = "MEDIUM"
    else:
        prediction = "NORMAL"
        risk_level = "LOW"

    return {
        "prediction": prediction,
        "fraud_score": round(final_score, 2),
        "risk_level": risk_level,
        "semantic_score": semantic_score,
        "indicators": model_b.get("indicators", []) if model_b else [],
        "explanation": model_b.get("explanation", "") if model_b else "",
        "warning": (
            "Experimental risk assessment; not a calibrated probability "
            "or definitive determination."
        )
    }


def add_audio_segment(session_id, filename, audio_bytes):
    if not audio_bytes:
        raise ValueError("Audio segment is empty.")

    if len(audio_bytes) > 25 * 1024 * 1024:
        raise ValueError("Audio segment exceeds the 25 MB limit.")

    session = get_session(session_id)

    with session.lock:
        if session.finished:
            raise ValueError("Call session has already ended.")

        segment_number = session.segment_count + 1
        safe_filename = f"segment_{segment_number:05d}.wav"
        audio_path = session.audio_dir / safe_filename

        audio_path.write_bytes(audio_bytes)

        try:
            transcription = transcribe_audio(
                filename or safe_filename,
                audio_bytes
            )
        except Exception:
            audio_path.unlink(missing_ok=True)
            raise

        segment_transcript = transcription.get("transcript", "").strip()

        if segment_transcript:
            session.transcript_parts.append(segment_transcript)

        session.segment_count = segment_number

        full_transcript = session.transcript

        analysis = analyze_transcript(full_transcript)

        return {
            "session_id": session.session_id,
            "segment_number": segment_number,
            "segment_transcript": segment_transcript,
            "full_transcript": full_transcript,
            "transcription": transcription,
            "analysis": analysis,
            "status": "ACTIVE"
        }


def finish_session(session_id):
    session = get_session(session_id)

    with session.lock:
        if session.finished:
            raise ValueError("Call session has already ended.")

        session.finished = True
        full_transcript = session.transcript

        try:
            final_analysis = analyze_transcript(full_transcript)
        finally:
            shutil.rmtree(session.audio_dir, ignore_errors=True)

        result = {
            "session_id": session.session_id,
            "status": "COMPLETED",
            "segment_count": session.segment_count,
            "full_transcript": full_transcript,
            "final_analysis": final_analysis,
            "created_at": session.created_at,
            "ended_at": datetime.now(timezone.utc).isoformat()
        }

    with _sessions_lock:
        _sessions.pop(session_id, None)

    return result