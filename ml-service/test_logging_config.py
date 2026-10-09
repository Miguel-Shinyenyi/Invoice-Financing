import json
import logging

from fastapi import FastAPI
from fastapi.testclient import TestClient

from logging_config import JsonFormatter, RequestIdLogFilter, install_request_id_middleware, request_id_ctx_var


def test_json_formatter_produces_valid_json_with_expected_fields():
    formatter = JsonFormatter()
    record = logging.LogRecord(
        name="test.logger", level=logging.INFO, pathname=__file__, lineno=1,
        msg="something happened", args=(), exc_info=None,
    )
    record.request_id = "abc-123"

    parsed = json.loads(formatter.format(record))

    assert parsed["level"] == "INFO"
    assert parsed["logger"] == "test.logger"
    assert parsed["message"] == "something happened"
    assert parsed["request_id"] == "abc-123"
    assert "timestamp" in parsed


def test_request_id_log_filter_injects_current_context_value():
    token = request_id_ctx_var.set("ctx-value")
    try:
        record = logging.LogRecord(
            name="test.logger", level=logging.INFO, pathname=__file__, lineno=1,
            msg="msg", args=(), exc_info=None,
        )

        result = RequestIdLogFilter().filter(record)

        assert result is True
        assert record.request_id == "ctx-value"
    finally:
        request_id_ctx_var.reset(token)


def test_request_id_log_filter_defaults_to_dash_when_no_request_in_flight():
    record = logging.LogRecord(
        name="test.logger", level=logging.INFO, pathname=__file__, lineno=1,
        msg="msg", args=(), exc_info=None,
    )

    RequestIdLogFilter().filter(record)

    assert record.request_id == "-"


def _sample_app():
    app = FastAPI()
    install_request_id_middleware(app)

    @app.get("/ping")
    def ping():
        return {"seen_request_id": request_id_ctx_var.get()}

    return app


def test_middleware_generates_a_request_id_when_none_supplied():
    client = TestClient(_sample_app())

    response = client.get("/ping")

    assert response.status_code == 200
    assert response.headers["X-Request-Id"]
    assert response.json()["seen_request_id"] == response.headers["X-Request-Id"]


def test_middleware_propagates_an_incoming_request_id():
    client = TestClient(_sample_app())

    response = client.get("/ping", headers={"X-Request-Id": "caller-supplied"})

    assert response.headers["X-Request-Id"] == "caller-supplied"
    assert response.json()["seen_request_id"] == "caller-supplied"
