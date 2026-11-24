import re
import hashlib
import uuid

from datetime import datetime, timezone, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.database.connection import get_db
from app.database.models import CallerProfile, CommunityReport
from app.schemas.reputation import (
    ReportCreate,
    ALLOWED_CATEGORIES,
)
from app.services.fraud_detection import fraud_detector
router = APIRouter(tags=["Community Reputation"])


def normalize_phone_number(phone_number: str) -> str:
    """
    Basic normalization.
    Accepts international numbers beginning with +.
    Full country-aware validation will be added separately.
    """
    normalized = re.sub(r"[^\d+]", "", phone_number.strip())

    if not re.fullmatch(r"\+[1-9]\d{6,14}", normalized):
        raise HTTPException(
            status_code=422,
            detail="Enter a valid international phone number, e.g. +919876543210",
        )

    return normalized


@router.post("/reports", status_code=201)
def submit_report(
    report: ReportCreate,
    db: Session = Depends(get_db),
):
    phone_number = normalize_phone_number(report.phone_number)
    category = report.category.strip().upper()

    if category not in ALLOWED_CATEGORIES:
        raise HTTPException(
            status_code=400,
            detail={
                "message": "Unsupported report category",
                "allowed_categories": sorted(ALLOWED_CATEGORIES),
            },
        )

    try:
        reporter_id = str(uuid.UUID(report.reporter_id))
    except ValueError:
        raise HTTPException(
            status_code=422,
            detail="Invalid reporter installation ID.",
        )

    evidence_text = report.evidence_text.strip()

    if len(evidence_text) < 20:
        raise HTTPException(
            status_code=422,
            detail="Supporting evidence must contain at least 20 characters.",
        )

    try:
        analysis = fraud_detector.analyze(evidence_text)
        evidence_score = analysis["fraud_score"]
    except Exception:
        raise HTTPException(
            status_code=503,
            detail="Evidence analysis is currently unavailable.",
        )

    evidence_hash = hashlib.sha256(
        evidence_text.encode("utf-8")
    ).hexdigest()

    if evidence_score < 70:
        raise HTTPException(
            status_code=422,
            detail="Evidence did not meet the current automated screening threshold.",
        )

    caller = (
        db.query(CallerProfile)
        .filter(CallerProfile.phone_number == phone_number)
        .first()
    )

    if caller is None:
        caller = CallerProfile(phone_number=phone_number)
        db.add(caller)
        db.flush()

    cutoff = datetime.now(timezone.utc) - timedelta(days=10)

    duplicate = (
        db.query(CommunityReport)
        .filter(
            CommunityReport.caller_id == caller.id,
            CommunityReport.reporter_id == reporter_id,
            CommunityReport.created_at >= cutoff,
        )
        .first()
    )

    if duplicate:
        raise HTTPException(
            status_code=409,
            detail="This installation has already reported this number within 10 days.",
        )

    new_report = CommunityReport(
        caller_id=caller.id,
        category=category,
        description=report.description,
        reporter_id=reporter_id,
        evidence_score=evidence_score,
        evidence_hash=evidence_hash,
        status="PENDING",
    )

    db.add(new_report)
    db.flush()

    qualifying_reporters = (
        db.query(CommunityReport.reporter_id)
        .filter(
            CommunityReport.caller_id == caller.id,
            CommunityReport.reporter_id.isnot(None),
            CommunityReport.evidence_score >= 70,
            CommunityReport.created_at >= cutoff,
            CommunityReport.status.in_(["PENDING", "ACCEPTED"]),
        )
        .distinct()
        .count()
    )

    auto_accepted = qualifying_reporters >= 30

    if auto_accepted:
        db.query(CommunityReport).filter(
            CommunityReport.caller_id == caller.id,
            CommunityReport.reporter_id.isnot(None),
            CommunityReport.evidence_score >= 70,
            CommunityReport.created_at >= cutoff,
            CommunityReport.status == "PENDING",
        ).update(
            {"status": "ACCEPTED"},
            synchronize_session=False,
        )

    db.commit()
    db.refresh(new_report)

    return {
        "message": "Report submitted successfully",
        "report_id": new_report.id,
        "phone_number": phone_number,
        "category": new_report.category,
        "status": new_report.status,
        "evidence_score": evidence_score,
        "qualifying_installations": qualifying_reporters,
        "auto_moderation_triggered": auto_accepted,
    }

@router.get("/reports/{phone_number}")
def get_reports(
    phone_number: str,
    db: Session = Depends(get_db),
):
    normalized = normalize_phone_number(phone_number)

    caller = (
        db.query(CallerProfile)
        .filter(CallerProfile.phone_number == normalized)
        .first()
    )

    if caller is None:
        return {
            "phone_number": normalized,
            "total_reports": 0,
            "reports": [],
        }

    reports = (
        db.query(CommunityReport)
        .filter(CommunityReport.caller_id == caller.id)
        .order_by(CommunityReport.created_at.desc())
        .all()
    )

    return {
        "phone_number": normalized,
        "total_reports": len(reports),
        "reports": [
            {
                "id": item.id,
                "category": item.category,
                "description": item.description,
                "status": item.status,
                "created_at": item.created_at,
            }
            for item in reports
        ],
    }


@router.get("/reputation/{phone_number}")
def get_reputation(
    phone_number: str,
    db: Session = Depends(get_db),
):
    normalized = normalize_phone_number(phone_number)

    caller = (
        db.query(CallerProfile)
        .filter(CallerProfile.phone_number == normalized)
        .first()
    )

    if caller is None:
        return {
            "phone_number": normalized,
            "reputation": "UNVERIFIED",
            "accepted_reports": 0,
            "pending_reports": 0,
            "message": "No community reports found.",
        }

    accepted_reports = (
        db.query(CommunityReport)
        .filter(
            CommunityReport.caller_id == caller.id,
            CommunityReport.status == "ACCEPTED",
        )
        .count()
    )

    pending_reports = (
        db.query(CommunityReport)
        .filter(
            CommunityReport.caller_id == caller.id,
            CommunityReport.status == "PENDING",
        )
        .count()
    )

    return {
        "phone_number": normalized,
        "reputation": (
            "COMMUNITY_REPORTED"
            if accepted_reports > 0
            else "UNVERIFIED"
        ),
        "accepted_reports": accepted_reports,
        "pending_reports": pending_reports,
        "message": (
            "Accepted community reports exist."
            if accepted_reports > 0
            else "Not enough accepted community evidence."
        ),
    }
