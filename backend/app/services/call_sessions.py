
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
        self.conversation_segments = []

        self.segment_count = 0
        self.audio_dir = Path(
            tempfile.mkdtemp(prefix="vaniguard_call_")
        )

        self.lock = threading.RLock()
        self.finished = False
        self.final_result = None

        # chunk_id -> original successful response
        self.processed_chunks = {}


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
            "recommended_action": "CONTINUE",
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

    if final_score > 70:
        prediction = "FRAUD"
        risk_level = "HIGH"
        recommended_action = "WARN_USER"

    elif final_score >= 40:
        prediction = "SUSPICIOUS"
        risk_level = "MEDIUM"
        recommended_action = "CONTINUE"

    else:
        prediction = "NORMAL"
        risk_level = "LOW"
        recommended_action = "CONTINUE"

    return {
        "prediction": prediction,
        "fraud_score": round(final_score, 2),
        "risk_level": risk_level,
        "semantic_score": semantic_score,
        "indicators": (
            model_b.get("indicators", [])
            if model_b else []
        ),
        "explanation": (
            model_b.get("explanation", "")
            if model_b else ""
        ),
        "recommended_action": recommended_action,
        "warning": (
            "Experimental risk assessment; not a calibrated "
            "probability or definitive determination."
        )
    }


def add_audio_segment(
    session_id,
    filename,
    audio_bytes,
    chunk_id=None,
    call_offset_ms=None
):
    if not audio_bytes:
        raise ValueError("Audio segment is empty.")

    if len(audio_bytes) > 25 * 1024 * 1024:
        raise ValueError("Audio segment exceeds the 25 MB limit.")

    session = get_session(session_id)

    # Legacy callers without chunk IDs still work.
    chunk_id = str(chunk_id or uuid.uuid4())

    with session.lock:
        if session.finished:
            raise ValueError("Call session has already ended.")

        # Return the previous result for a retried upload.
        if chunk_id in session.processed_chunks:
            previous = session.processed_chunks[chunk_id]
            return {
                **previous,
                "duplicate": True
            }

        segment_number = session.segment_count + 1

        safe_filename = f"segment_{segment_number:05d}.wav"
        audio_path = session.audio_dir / safe_filename

        # Keep the received audio until transcription succeeds.
        audio_path.write_bytes(audio_bytes)

        try:
            transcription = transcribe_audio(
                filename or safe_filename,
                audio_bytes
            )
        except Exception:
            # Preserve the file for diagnosis/retry during the
            # active session. Do not append a transcript.
            raise

        segment_transcript = (
            transcription.get("transcript", "").strip()
        )

        received_at = datetime.now(timezone.utc).isoformat()

        offset_ms = (
            max(0, int(call_offset_ms))
            if call_offset_ms is not None
            else None
        )

        whisper_segments = transcription.get("segments", [])

        if whisper_segments:
            for item in whisper_segments:
                start_seconds = float(item.get("start", 0))
                end_seconds = float(item.get("end", start_seconds))

                entry = {
                    "segment_number": segment_number,
                    "chunk_id": chunk_id,
                    "speaker": "UNKNOWN",
                    "text": item.get("text", "").strip(),
                    "start_seconds": round(start_seconds, 3),
                    "end_seconds": round(end_seconds, 3),
                    "call_offset_ms": offset_ms,
                    "received_at": received_at
                }

                if entry["text"]:
                    session.conversation_segments.append(entry)

        elif segment_transcript:
            session.conversation_segments.append({
                "segment_number": segment_number,
                "chunk_id": chunk_id,
                "speaker": "UNKNOWN",
                "text": segment_transcript,
                "start_seconds": None,
                "end_seconds": None,
                "call_offset_ms": offset_ms,
                "received_at": received_at
            })

        if segment_transcript:
            session.transcript_parts.append(segment_transcript)

        session.segment_count = segment_number

        full_transcript = session.transcript
        analysis = analyze_transcript(full_transcript)

        # Transcription succeeded, so the temporary WAV is no
        # longer required by this session.
        audio_path.unlink(missing_ok=True)

        result = {
            "session_id": session.session_id,
            "segment_number": segment_number,
            "chunk_id": chunk_id,
            "segment_transcript": segment_transcript,
            "full_transcript": full_transcript,
            "transcription": transcription,
            "conversation_segments": list(
                session.conversation_segments
            ),
            "analysis": analysis,
            "status": "ACTIVE",
            "duplicate": False
        }

        session.processed_chunks[chunk_id] = result

        return result


def finish_session(session_id):
    session = get_session(session_id)

    with session.lock:
        # Idempotent finalization.
        if session.final_result is not None:
            return session.final_result

        full_transcript = session.transcript

        # Do not mark the session finished until analysis succeeds.
        final_analysis = analyze_transcript(full_transcript)

        result = {
            "session_id": session.session_id,
            "status": "COMPLETED",
            "segment_count": session.segment_count,
            "full_transcript": full_transcript,
            "conversation_segments": list(
                session.conversation_segments
            ),
            "final_analysis": final_analysis,
            "created_at": session.created_at,
            "ended_at": datetime.now(timezone.utc).isoformat()
        }

        session.finished = True
        session.final_result = result

        shutil.rmtree(session.audio_dir, ignore_errors=True)

    with _sessions_lock:
        _sessions.pop(session_id, None)

    return result
