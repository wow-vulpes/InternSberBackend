CREATE SCHEMA kkd;

CREATE TABLE kkd.unit (
    id text PRIMARY KEY CHECK (id ~ '[^[:space:]]'),
    name text NOT NULL CHECK (name ~ '[^[:space:]]'),
    parent_id text REFERENCES kkd.unit(id),
    CHECK (parent_id <> id)
);
CREATE INDEX unit_parent_idx ON kkd.unit(parent_id);

CREATE TABLE kkd.goal (
    id text PRIMARY KEY CHECK (id ~ '[^[:space:]]'),
    title text NOT NULL CHECK (title ~ '[^[:space:]]'),
    unit_id text NOT NULL REFERENCES kkd.unit(id),
    owner_id text,
    parent_goal_id text REFERENCES kkd.goal(id),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    deadline date CHECK (isfinite(deadline)),
    CHECK (parent_goal_id <> id)
);
CREATE INDEX goal_unit_idx ON kkd.goal(unit_id);
CREATE INDEX goal_parent_idx ON kkd.goal(parent_goal_id);

CREATE TABLE kkd.key_result (
    id text PRIMARY KEY CHECK (id ~ '[^[:space:]]'),
    title text NOT NULL CHECK (title ~ '[^[:space:]]'),
    goal_id text NOT NULL REFERENCES kkd.goal(id),
    target_value numeric CHECK (target_value NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    actual_value numeric CHECK (actual_value NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    weight numeric NOT NULL CHECK (weight NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)),
    progress_updated_on date CHECK (isfinite(progress_updated_on))
);
CREATE INDEX key_result_goal_idx ON kkd.key_result(goal_id);
