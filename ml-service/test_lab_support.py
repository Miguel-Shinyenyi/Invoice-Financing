import json
import logging
import os
import subprocess
import sys

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from lab_support import MAX_FAULT_SECONDS, install_lab
from logging_config import RequestIdLogFilter, install_request_id_middleware


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def app_and_logger(clock):
    app = FastAPI()
    install_request_id_middleware(app)

    @app.post("/score")
    def score():
        return {"score": 0.0, "decision": "ALLOW", "reasons": []}

    @app.get("/metrics")
    def metrics():
        return "ok"

    @app.get("/health")
    def health():
        return {"status": "ok"}

    logger = logging.getLogger(f"lab-test-{id(app)}")
    logger.setLevel(logging.DEBUG)
    logger.propagate = False
    handler = install_lab(app, clock=clock, ring_size=5, logger=logger)
    logger.addFilter(RequestIdLogFilter())
    yield app, logger
    logger.removeHandler(handler)


def test_lab_routes_are_absent_without_the_flag():
    code = (
        "import os; os.environ.pop('ML_LAB', None); import main; "
        "import json; print(json.dumps([r.path for r in main.app.routes]))"
    )
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, cwd=os.path.dirname(__file__))
    paths = json.loads(out.stdout.strip().splitlines()[-1])
    assert "/lab/logs" not in paths
    assert "/lab/fault" not in paths
    assert "/score" in paths


def test_lab_routes_exist_with_the_flag():
    code = (
        "import os; os.environ['ML_LAB'] = '1'; import main; "
        "import json; print(json.dumps([r.path for r in main.app.routes]))"
    )
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, cwd=os.path.dirname(__file__))
    paths = json.loads(out.stdout.strip().splitlines()[-1])
    assert "/lab/logs" in paths
    assert "/lab/fault" in paths


def test_logs_endpoint_returns_structured_events_with_the_service_name(app_and_logger):
    app, logger = app_and_logger
    client = TestClient(app)
    logger.info("scored invoice")
    body = client.get("/lab/logs").json()
    assert body["events"][-1]["message"] == "scored invoice"
    assert body["events"][-1]["service"] == "ml-service"
    assert body["events"][-1]["level"] == "INFO"
    assert "timestamp" in body["events"][-1]
    assert body["capacity"] == 5


def test_ring_buffer_is_capped(app_and_logger):
    app, logger = app_and_logger
    for i in range(12):
        logger.info("msg %d", i)
    events = TestClient(app).get("/lab/logs?limit=100").json()["events"]
    assert len(events) == 5
    assert events[0]["message"] == "msg 7"
    assert events[-1]["message"] == "msg 11"


def test_logs_filter_by_level_text_and_request_id(app_and_logger):
    app, logger = app_and_logger
    client = TestClient(app)
    logger.info("alpha")
    logger.warning("beta needle")
    logger.error("gamma")
    assert [e["message"] for e in client.get("/lab/logs?level=WARNING").json()["events"]] == ["beta needle", "gamma"]
    assert [e["message"] for e in client.get("/lab/logs?text=NEEDLE").json()["events"]] == ["beta needle"]

    from logging_config import request_id_ctx_var
    token = request_id_ctx_var.set("req-42")
    try:
        logger.info("with request id")
    finally:
        request_id_ctx_var.reset(token)
    ids = client.get("/lab/logs?request_id=req-42").json()["events"]
    assert [e["message"] for e in ids] == ["with request id"]
    assert ids[0]["requestId"] == "req-42"


def test_logs_since_filter_uses_the_event_timestamp(app_and_logger):
    app, logger = app_and_logger
    client = TestClient(app)
    logger.info("old")
    assert client.get("/lab/logs?since=2999-01-01T00:00:00Z").json()["events"] == []
    assert len(client.get("/lab/logs?since=2000-01-01T00:00:00Z").json()["events"]) == 1
    assert client.get("/lab/logs?since=garbage").status_code == 400


def test_fault_makes_score_and_metrics_return_503_but_health_stays_up(app_and_logger):
    app, _ = app_and_logger
    client = TestClient(app)
    assert client.post("/score").status_code == 200
    assert client.post("/lab/fault", json={"seconds": 30}).status_code == 200
    assert client.post("/score").status_code == 503
    assert client.get("/metrics").status_code == 503
    assert client.get("/health").status_code == 200


def test_fault_auto_recovers_after_its_duration(app_and_logger, clock):
    app, _ = app_and_logger
    client = TestClient(app)
    client.post("/lab/fault", json={"seconds": 20})
    clock.now += 19
    assert client.post("/score").status_code == 503
    clock.now += 2
    assert client.post("/score").status_code == 200
    assert client.get("/metrics").status_code == 200


def test_fault_duration_is_capped_at_sixty_seconds_and_validated(app_and_logger, clock):
    app, _ = app_and_logger
    client = TestClient(app)
    assert MAX_FAULT_SECONDS == 60
    assert client.post("/lab/fault", json={"seconds": 61}).status_code == 400
    assert client.post("/lab/fault", json={"seconds": 0}).status_code == 400
    assert client.post("/lab/fault", json={"seconds": "soon"}).status_code in (400, 422)
    assert client.post("/lab/fault", json={"seconds": 60}).status_code == 200
    clock.now += 61
    assert client.post("/score").status_code == 200


def test_fault_status_reports_remaining_seconds(app_and_logger, clock):
    app, _ = app_and_logger
    client = TestClient(app)
    assert client.get("/lab/fault").json() == {"active": False, "remainingSeconds": 0}
    client.post("/lab/fault", json={"seconds": 10})
    clock.now += 4
    status = client.get("/lab/fault").json()
    assert status["active"] is True
    assert status["remainingSeconds"] == pytest.approx(6, abs=0.01)


def test_lab_routes_themselves_are_never_blocked_by_the_fault(app_and_logger):
    app, _ = app_and_logger
    client = TestClient(app)
    client.post("/lab/fault", json={"seconds": 30})
    assert client.get("/lab/logs").status_code == 200
    assert client.get("/lab/fault").status_code == 200
