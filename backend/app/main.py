
from fastapi import FastAPI

from app.database.connection import Base, engine
from app.database import models
from app.api.reputation import router as reputation_router
from app.api.fraud import router as fraud_router
from app.api.admin import router as admin_router

Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="VaniGuard API",
    description="Community-powered caller reputation and fraud intelligence backend",
    version="1.0.0",
)

app.include_router(reputation_router)
app.include_router(fraud_router)
app.include_router(admin_router)


@app.get("/")
def root():
    return {
        "application": "VaniGuard",
        "status": "running",
        "message": "VaniGuard backend is operational",
    }


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "database": "connected",
    }
