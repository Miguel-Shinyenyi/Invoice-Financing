from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def base_request(**overrides):
    request = {
        "invoice_id": "11111111-1111-1111-1111-111111111111",
        "business_account_id": "22222222-2222-2222-2222-222222222222",
        "customer_reference": "acme-corp",
        "amount": 1000.0,
        "currency": "USD",
        "requested_advance_amount": 800.0,
        "business_account_age_days": 365,
        "duplicate_customer_reference_count": 0,
        "outstanding_advance_count": 0,
    }
    request.update(overrides)
    return request


def test_health_check():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_clean_invoice_scores_zero_and_allows():
    response = client.post("/score", json=base_request())

    assert response.status_code == 200
    body = response.json()
    assert body["score"] == 0.0
    assert body["decision"] == "ALLOW"
    assert body["reasons"] == []


def test_duplicate_customer_reference_flagged_but_allowed_alone():
    response = client.post("/score", json=base_request(duplicate_customer_reference_count=1))

    body = response.json()
    assert body["score"] == 0.4
    assert body["decision"] == "ALLOW"
    assert "duplicate_customer_reference" in body["reasons"]


def test_new_account_with_high_advance_flagged_but_allowed_alone():
    response = client.post("/score", json=base_request(business_account_age_days=2, requested_advance_amount=800.0))

    body = response.json()
    assert body["score"] == 0.3
    assert body["decision"] == "ALLOW"
    assert "new_account_high_advance" in body["reasons"]


def test_new_account_with_low_advance_is_not_flagged():
    response = client.post("/score", json=base_request(business_account_age_days=2, requested_advance_amount=100.0))

    body = response.json()
    assert body["score"] == 0.0
    assert "new_account_high_advance" not in body["reasons"]


def test_rapid_refinancing_flagged_but_allowed_alone():
    response = client.post("/score", json=base_request(outstanding_advance_count=3))

    body = response.json()
    assert body["score"] == 0.3
    assert body["decision"] == "ALLOW"
    assert "rapid_refinancing" in body["reasons"]


def test_duplicate_and_new_account_combined_blocks():
    response = client.post("/score", json=base_request(
        duplicate_customer_reference_count=1,
        business_account_age_days=2,
        requested_advance_amount=800.0,
    ))

    body = response.json()
    assert body["score"] == 0.7
    assert body["decision"] == "BLOCK"
    assert set(body["reasons"]) == {"duplicate_customer_reference", "new_account_high_advance"}


def test_all_signals_combined_caps_score_at_one_and_blocks():
    response = client.post("/score", json=base_request(
        duplicate_customer_reference_count=2,
        business_account_age_days=1,
        requested_advance_amount=900.0,
        outstanding_advance_count=5,
    ))

    body = response.json()
    assert body["score"] == 1.0
    assert body["decision"] == "BLOCK"


def test_malformed_request_returns_422():
    response = client.post("/score", json={"invoice_id": "not-enough-fields"})

    assert response.status_code == 422
