CREATE TABLE kkd.kkd_run (
    id text PRIMARY KEY CHECK (id ~ '[^[:space:]]'),
    snapshot_date date NOT NULL CHECK (isfinite(snapshot_date)),
    status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    compared_to_id text REFERENCES kkd.kkd_run(id),
    started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (isfinite(started_at)),
    finished_at timestamptz CHECK (isfinite(finished_at)),
    error_code text,
    CHECK (compared_to_id <> id),
    CHECK (finished_at >= started_at),
    CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL)
        OR (status = 'COMPLETED' AND finished_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL
            AND error_code ~ '[^[:space:]]')
    )
);
CREATE INDEX run_started_idx ON kkd.kkd_run(started_at DESC, id);
CREATE INDEX run_compared_idx ON kkd.kkd_run(compared_to_id);

CREATE TABLE kkd.unit_snapshot (
    run_id text NOT NULL REFERENCES kkd.kkd_run(id),
    id text NOT NULL CHECK (id ~ '[^[:space:]]'),
    name text NOT NULL CHECK (name ~ '[^[:space:]]'),
    parent_id text,
    PRIMARY KEY (run_id, id),
    FOREIGN KEY (run_id, parent_id) REFERENCES kkd.unit_snapshot(run_id, id),
    CHECK (parent_id <> id)
);
CREATE INDEX unit_snapshot_parent_idx ON kkd.unit_snapshot(run_id, parent_id);

CREATE TABLE kkd.goal_snapshot (
    run_id text NOT NULL REFERENCES kkd.kkd_run(id),
    id text NOT NULL CHECK (id ~ '[^[:space:]]'),
    title text NOT NULL CHECK (title ~ '[^[:space:]]'),
    unit_id text NOT NULL,
    owner_id text,
    parent_goal_id text,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    deadline date CHECK (isfinite(deadline)),
    PRIMARY KEY (run_id, id),
    FOREIGN KEY (run_id, unit_id) REFERENCES kkd.unit_snapshot(run_id, id),
    FOREIGN KEY (run_id, parent_goal_id) REFERENCES kkd.goal_snapshot(run_id, id),
    CHECK (parent_goal_id <> id)
);
CREATE INDEX goal_snapshot_unit_idx ON kkd.goal_snapshot(run_id, unit_id);
CREATE INDEX goal_snapshot_parent_idx ON kkd.goal_snapshot(run_id, parent_goal_id);

CREATE TABLE kkd.key_result_snapshot (
    run_id text NOT NULL REFERENCES kkd.kkd_run(id),
    id text NOT NULL CHECK (id ~ '[^[:space:]]'),
    title text NOT NULL CHECK (title ~ '[^[:space:]]'),
    goal_id text NOT NULL,
    target_value numeric CHECK (target_value NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    actual_value numeric CHECK (actual_value NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    weight numeric NOT NULL CHECK (weight NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    progress_updated_on date CHECK (isfinite(progress_updated_on)),
    PRIMARY KEY (run_id, id),
    UNIQUE (run_id, goal_id, id),
    FOREIGN KEY (run_id, goal_id) REFERENCES kkd.goal_snapshot(run_id, id)
);
