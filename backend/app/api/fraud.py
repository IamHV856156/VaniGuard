
from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from pydantic import BaseModel, Field

from app.services.fraud_detection import fraud_detector
from app.services.call_sessions import (
    create_session,
    add_audio_segment,
    finish_session
)
from app.transcription import transcribe_audio
from app.scam_detector import detect_scam

router = APIRouter(prefix="/fraud", tags=["Fraud Detection"])


class FraudAnalysisRequest(BaseModel):
    text: str = Field(min_length=1, max_length=50000)


@router.post("/analyze")
def analyze_conversation(request: FraudAnalysisRequest):
    try:
        return fraud_detector.analyze(request.text)

    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error))

    except Exception:
        raise HTTPException(
            status_code=500,
            detail="Fraud analysis failed."
        )


@router.post("/analyze-audio")
async def analyze_audio(file: UploadFile = File(...)):
    try:
        audio_bytes = await file.read()

        if not audio_bytes:
            raise HTTPException(
                status_code=422,
                detail="Audio file is empty."
            )

        if len(audio_bytes) > 25 * 1024 * 1024:
            raise HTTPException(
                status_code=413,
                detail="Audio file exceeds the 25 MB limit."
            )

        transcription = transcribe_audio(
            file.filename or "recording.wav",
            audio_bytes
        )

        transcript = transcription["transcript"]
        model_a = fraud_detector.analyze(transcript)

        model_b = None

        try:
            model_b = detect_scam(transcript)
        except Exception as error:
            print(f"Semantic analysis unavailable: {error}")

        model_a_score = model_a["fraud_score"]

        if model_b:
            semantic_score = model_b["semantic_score"]
            final_score = max(model_a_score, semantic_score)
        else:
            semantic_score = None
            final_score = model_a_score

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
            "transcription": transcription,
            "model_a": model_a,
            "model_b": model_b,
            "final_analysis": {
                "prediction": prediction,
                "fraud_score": round(final_score, 2),
                "risk_level": risk_level,
                "semantic_score": semantic_score,
                "warning": (
                    "Experimental risk assessment; "
                    "not a calibrated probability or definitive determination."
                )
            }
        }

    except HTTPException:
        raise

    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error))

    except RuntimeError as error:
        raise HTTPException(status_code=503, detail=str(error))

    except Exception:
        raise HTTPException(
            status_code=500,
            detail="Audio fraud analysis failed."
        )

    finally:
        await file.close()


@router.post("/sessions")
def start_call_session():
    return create_session()


@router.post("/sessions/{session_id}/segments")
async def upload_call_segment(
    session_id: str,
    file: UploadFile = File(...),
    chunk_id: str | None = Form(default=None),
    call_offset_ms: int | None = Form(default=None)
):
    try:
        audio_bytes = await file.read()

        if not audio_bytes:
            raise HTTPException(
                status_code=422,
                detail="Audio segment is empty."
            )

        result = add_audio_segment(
            session_id=session_id,
            filename=file.filename or "segment.wav",
            audio_bytes=audio_bytes,
            chunk_id=chunk_id,
            call_offset_ms=call_offset_ms
        )

        return result

    except HTTPException:
        raise

    except ValueError as error:
        raise HTTPException(
            status_code=422,
            detail=str(error)
        )

    except RuntimeError as error:
        raise HTTPException(
            status_code=503,
            detail=str(error)
        )

    except Exception:
        raise HTTPException(
            status_code=500,
            detail="Call segment processing failed."
        )

    finally:
        await file.close()


@router.post("/sessions/{session_id}/finish")
def complete_call_session(session_id: str):
    try:
        return finish_session(session_id)

    except KeyError:
        raise HTTPException(
            status_code=404,
            detail="Call session not found."
        )

    except ValueError as error:
        raise HTTPException(
            status_code=422,
            detail=str(error)
        )

    except Exception:
        raise HTTPException(
            status_code=500,
            detail="Call session finalization failed."
        )
