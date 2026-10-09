-- Lab-only table (loaded only under the demo profile via spring.flyway.locations). Holds the last
-- load runs so the history survives a page reload but is cleared by a reset.
CREATE TABLE lab_load_runs (
    id UUID PRIMARY KEY,
    scenario VARCHAR(40) NOT NULL,
    plan_json TEXT NOT NULL,
    result_json TEXT,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
