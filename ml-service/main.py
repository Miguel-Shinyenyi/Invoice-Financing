from typing import List
from uuid import UUID

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Fraud Detection Service")


class ScoreRequest(BaseModel):
    invoice_id: UUID
    business_account_id: UUID
    customer_reference: str
    amount: float
    currency: str
    requested_advance_amount: float
    business_account_age_days: int
    duplicate_customer_reference_count: int
    outstanding_advance_count: int


class ScoreResponse(BaseModel):
    score: float
    decision: str
    reasons: List[str]


NEW_ACCOUNT_AGE_DAYS_THRESHOLD = 7
NEW_ACCOUNT_ADVANCE_AMOUNT_THRESHOLD = 500.0
RAPID_REFINANCING_OUTSTANDING_ADVANCE_THRESHOLD = 3
BLOCK_SCORE_THRESHOLD = 0.7


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/score", response_model=ScoreResponse)
def score(request: ScoreRequest) -> ScoreResponse:
    score_value = 0.0
    reasons: List[str] = []

    if request.duplicate_customer_reference_count > 0:
        score_value += 0.4
        reasons.append("duplicate_customer_reference")

    if (
        request.business_account_age_days < NEW_ACCOUNT_AGE_DAYS_THRESHOLD
        and request.requested_advance_amount > NEW_ACCOUNT_ADVANCE_AMOUNT_THRESHOLD
    ):
        score_value += 0.3
        reasons.append("new_account_high_advance")

    if request.outstanding_advance_count >= RAPID_REFINANCING_OUTSTANDING_ADVANCE_THRESHOLD:
        score_value += 0.3
        reasons.append("rapid_refinancing")

    score_value = min(score_value, 1.0)
    decision = "BLOCK" if score_value >= BLOCK_SCORE_THRESHOLD else "ALLOW"

    return ScoreResponse(score=score_value, decision=decision, reasons=reasons)
