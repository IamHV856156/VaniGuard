from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


ALLOWED_CATEGORIES = {
    "SCAM",
    "OTP_FRAUD",
    "BANK_FRAUD",
    "TELEMARKETING",
    "HARASSMENT",
    "IMPERSONATION",
}


class ReportCreate(BaseModel):
    phone_number: str = Field(min_length=7, max_length=20)
    category: str
    description: Optional[str] = Field(default=None, max_length=1000)

    reporter_id: str = Field(min_length=36, max_length=36)
    evidence_text: str = Field(min_length=20, max_length=50000)


class ReportResponse(BaseModel):
    id: int
    phone_number: str
    category: str
    description: Optional[str]
    status: str
    created_at: datetime

    model_config = {"from_attributes": True}