
from datetime import datetime, timezone

from sqlalchemy import (
    Column,
    Integer,
    String,
    Text,
    DateTime,
    ForeignKey,
    Index,
    Float,
)
from sqlalchemy.orm import relationship

from app.database.connection import Base


class CallerProfile(Base):
    __tablename__ = "caller_profiles"

    id = Column(Integer, primary_key=True, index=True)
    phone_number = Column(String(20), unique=True, nullable=False, index=True)
    country_code = Column(String(5), nullable=True)
    region = Column(String(10), nullable=True)
    line_type = Column(String(30), nullable=True)
    carrier = Column(String(150), nullable=True)

    created_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )

    updated_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )

    reports = relationship(
        "CommunityReport",
        back_populates="caller",
        cascade="all, delete-orphan",
    )


class CommunityReport(Base):
    __tablename__ = "community_reports"

    id = Column(Integer, primary_key=True, index=True)

    caller_id = Column(
        Integer,
        ForeignKey("caller_profiles.id"),
        nullable=False,
    )

    category = Column(String(50), nullable=False)
    description = Column(Text, nullable=True)
    reporter_id = Column(String(36), nullable=True, index=True)
    evidence_score = Column(Float, nullable=True)
    evidence_hash = Column(String(64), nullable=True)

    status = Column(
        String(20),
        default="PENDING",
        nullable=False,
        index=True,
    )

    created_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )

    caller = relationship(
        "CallerProfile",
        back_populates="reports",
    )

    __table_args__ = (
        Index("idx_report_caller_status", "caller_id", "status"),
    )
