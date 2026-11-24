import secrets
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.config import settings
from app.database.connection import get_db
from app.database.models import CommunityReport

router = APIRouter(prefix="/admin", tags=["Admin"])


class ModerationRequest(BaseModel):
    status: Literal["ACCEPTED", "REJECTED"]


@router.post("/reports/{report_id}/moderate")
def moderate_report(
    report_id: int,
    request: ModerationRequest,
    x_admin_api_key: str | None = Header(
        default=None,
        alias="X-Admin-API-Key"
    ),
    db: Session = Depends(get_db),
):
    configured_key = settings.admin_api_key

    if not configured_key:
        raise HTTPException(
            status_code=503,
            detail="Admin moderation is not configured."
        )

    if not x_admin_api_key or not secrets.compare_digest(
        x_admin_api_key,
        configured_key
    ):
        raise HTTPException(
            status_code=401,
            detail="Invalid admin API key."
        )

    report = db.query(CommunityReport).filter(
        CommunityReport.id == report_id
    ).first()

    if report is None:
        raise HTTPException(
            status_code=404,
            detail="Report not found."
        )

    if report.status != "PENDING":
        raise HTTPException(
            status_code=409,
            detail=f"Report already has status {report.status}."
        )

    report.status = request.status
    db.commit()
    db.refresh(report)

    return {
        "message": "Report moderated successfully.",
        "report_id": report.id,
        "status": report.status,
    }