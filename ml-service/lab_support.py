"""Lab-only support for the public sandbox. Installed by main.py only when ML_LAB=1; without the flag none of
these routes exist (test_lab_support.py proves it). Two things live here: a ring buffer of this service's own
logs (served at GET /lab/logs so the backend can merge both services into one stream) and a time-boxed fault
that makes /score and /metrics answer 503, so MLServiceDown and the backend's fail-open behaviour can be
triggered by a real cause."""
import logging
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Callable, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

MAX_FAULT_SECONDS = 60
DEFAULT_RING_SIZE = 2000
SERVICE_NAME = "ml-service"
_LEVELS = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
_FAULTED_PATHS = ("/score", "/metrics")


class _RingHandler(logging.Handler):
    def __init__(self, size: int):
        super().__init__()
        self.events = deque(maxlen=size)
        self.size = size
        self._seq = 0
        self._lock = threading.Lock()

    def emit(self, record: logging.LogRecord) -> None:
        trace_id = getattr(record, "otelTraceID", None)
        event = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "service": SERVICE_NAME,
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "requestId": getattr(record, "request_id", None),
            "traceId": trace_id if trace_id and trace_id != "0" else None,
        }
        with self._lock:
            self._seq += 1
            event["seq"] = self._seq
            self.events.append(event)


class _FaultRequest(BaseModel):
    seconds: int


def install_lab(app: FastAPI, clock: Callable[[], float] = time.monotonic, ring_size: int = DEFAULT_RING_SIZE,
                logger: Optional[logging.Logger] = None) -> logging.Handler:
    handler = _RingHandler(ring_size)
    if logger is not None:
        logger.addHandler(handler)
    else:
        # uvicorn's loggers do not propagate to the root logger (see logging_config.configure_json_logging)
        for name in ("", "uvicorn", "uvicorn.access", "uvicorn.error"):
            logging.getLogger(name).addHandler(handler)
    state = {"until": 0.0}

    @app.middleware("http")
    async def fault_middleware(request: Request, call_next):
        if request.url.path in _FAULTED_PATHS and clock() < state["until"]:
            return JSONResponse(status_code=503, content={"detail": "lab fault injected"})
        return await call_next(request)

    @app.get("/lab/logs")
    def lab_logs(level: Optional[str] = None, logger: Optional[str] = None, request_id: Optional[str] = None,
                 text: Optional[str] = None, since: Optional[str] = None, limit: int = 200):
        since_dt = None
        if since:
            try:
                since_dt = datetime.fromisoformat(since.replace("Z", "+00:00"))
            except ValueError:
                raise HTTPException(status_code=400, detail="since must be an ISO-8601 instant")
        min_rank = _LEVELS.index(level.upper()) if level and level.upper() in _LEVELS else 0
        out = []
        for e in list(handler.events):
            rank = _LEVELS.index(e["level"]) if e["level"] in _LEVELS else 0
            if rank < min_rank:
                continue
            if logger and not e["logger"].startswith(logger):
                continue
            if request_id and e["requestId"] != request_id:
                continue
            if text and text.lower() not in e["message"].lower():
                continue
            if since_dt and datetime.fromisoformat(e["timestamp"]) < since_dt:
                continue
            out.append(e)
        limit = max(1, min(500, limit))
        return {"events": out[-limit:], "capacity": handler.size, "size": len(handler.events)}

    @app.post("/lab/fault")
    def start_fault(body: _FaultRequest):
        if body.seconds < 1 or body.seconds > MAX_FAULT_SECONDS:
            raise HTTPException(status_code=400, detail=f"seconds must be between 1 and {MAX_FAULT_SECONDS}")
        state["until"] = clock() + body.seconds
        return {"active": True, "remainingSeconds": body.seconds}

    @app.get("/lab/fault")
    def fault_status():
        remaining = max(0.0, state["until"] - clock())
        return {"active": remaining > 0, "remainingSeconds": remaining if remaining > 0 else 0}

    return handler
