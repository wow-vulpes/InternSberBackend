CREATE TABLE kkd.kkd_rule (
    code text PRIMARY KEY CHECK (code ~ '[^[:space:]]'),
    title text NOT NULL CHECK (title ~ '[^[:space:]]'),
    severity text NOT NULL CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR')),
    object_type text NOT NULL CHECK (object_type IN ('GOAL', 'KEY_RESULT')),
    UNIQUE (code, severity, object_type)
);
INSERT INTO kkd.kkd_rule(code, title, severity, object_type) VALUES
    ('GOAL_NO_OWNER', 'Цель без ответственного', 'CRITICAL', 'GOAL'),
    ('GOAL_NO_KR', 'Цель без ключевых результатов', 'CRITICAL', 'GOAL'),
    ('KR_WEIGHTS_SUM', 'Сумма весов КР не равна 100%', 'MAJOR', 'GOAL'),
    ('KR_STALE_PROGRESS', 'Устаревший факт КР', 'MINOR', 'KEY_RESULT');

CREATE TABLE kkd.kkd_issue (
    run_id text NOT NULL,
    rule_code text NOT NULL,
    severity text NOT NULL,
    goal_id text NOT NULL,
    key_result_id text,
    object_type text GENERATED ALWAYS AS (
        CASE WHEN key_result_id IS NULL THEN 'GOAL' ELSE 'KEY_RESULT' END
    ) STORED,
    object_id text GENERATED ALWAYS AS (COALESCE(key_result_id, goal_id)) STORED,
    PRIMARY KEY (run_id, rule_code, object_type, object_id),
    FOREIGN KEY (run_id, goal_id) REFERENCES kkd.goal_snapshot(run_id, id),
    FOREIGN KEY (run_id, goal_id, key_result_id) REFERENCES kkd.key_result_snapshot(run_id, goal_id, id),
    FOREIGN KEY (rule_code, severity, object_type) REFERENCES kkd.kkd_rule(code, severity, object_type)
);
CREATE INDEX issue_goal_idx ON kkd.kkd_issue(run_id, goal_id);
CREATE INDEX issue_severity_idx ON kkd.kkd_issue(run_id, severity);
