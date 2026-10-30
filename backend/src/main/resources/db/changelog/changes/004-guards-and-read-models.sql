CREATE FUNCTION kkd.validate_snapshot(p_run_id text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM kkd.kkd_run WHERE id = p_run_id) THEN
        RAISE EXCEPTION 'Unknown run' USING ERRCODE = '23503';
    END IF;
    IF EXISTS (
        WITH RECURSIVE walk AS (
            SELECT id, parent_id, ARRAY[id] AS path, false AS cycle
            FROM kkd.unit_snapshot WHERE run_id = p_run_id
            UNION ALL
            SELECT p.id, p.parent_id, w.path || p.id, p.id = ANY(w.path)
            FROM walk w
            JOIN kkd.unit_snapshot p ON p.run_id = p_run_id AND p.id = w.parent_id
            WHERE NOT w.cycle
        )
        SELECT 1 FROM walk WHERE cycle
    ) THEN
        RAISE EXCEPTION 'Unit hierarchy contains a cycle' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        WITH RECURSIVE walk AS (
            SELECT id, parent_goal_id, ARRAY[id] AS path, false AS cycle
            FROM kkd.goal_snapshot WHERE run_id = p_run_id
            UNION ALL
            SELECT p.id, p.parent_goal_id, w.path || p.id, p.id = ANY(w.path)
            FROM walk w
            JOIN kkd.goal_snapshot p ON p.run_id = p_run_id AND p.id = w.parent_goal_id
            WHERE NOT w.cycle
        )
        SELECT 1 FROM walk WHERE cycle
    ) THEN
        RAISE EXCEPTION 'Goal hierarchy contains a cycle' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION kkd.guard_run() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    previous_status text;
    previous_date date;
BEGIN
    IF TG_OP <> 'INSERT' AND OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'Terminal runs are immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RUNNING' THEN
            RAISE EXCEPTION 'New runs must start in RUNNING status' USING ERRCODE = '23514';
        END IF;
        IF NEW.compared_to_id IS NOT NULL THEN
            SELECT status, snapshot_date INTO previous_status, previous_date
            FROM kkd.kkd_run WHERE id = NEW.compared_to_id;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'Unknown compared run' USING ERRCODE = '23503';
            END IF;
            IF previous_status <> 'COMPLETED' OR previous_date > NEW.snapshot_date THEN
                RAISE EXCEPTION 'Compared run must be completed and not newer' USING ERRCODE = '23514';
            END IF;
        END IF;
    ELSIF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.snapshot_date IS DISTINCT FROM OLD.snapshot_date
        OR NEW.compared_to_id IS DISTINCT FROM OLD.compared_to_id
        OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'Run identity and comparison are immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'COMPLETED' THEN
        PERFORM kkd.validate_snapshot(NEW.id);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_run BEFORE INSERT OR UPDATE OR DELETE ON kkd.kkd_run
FOR EACH ROW EXECUTE FUNCTION kkd.guard_run();

CREATE FUNCTION kkd.guard_run_data() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target_run_id text;
    run_status text;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.run_id IS DISTINCT FROM OLD.run_id THEN
        RAISE EXCEPTION 'Data cannot move between runs' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        target_run_id := OLD.run_id;
    ELSE
        target_run_id := NEW.run_id;
    END IF;
    SELECT status INTO run_status FROM kkd.kkd_run WHERE id = target_run_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown run' USING ERRCODE = '23503';
    END IF;
    IF run_status <> 'RUNNING' THEN
        RAISE EXCEPTION 'Only running run data may change' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_unit_snapshot BEFORE INSERT OR UPDATE OR DELETE ON kkd.unit_snapshot
FOR EACH ROW EXECUTE FUNCTION kkd.guard_run_data();
CREATE TRIGGER guard_goal_snapshot BEFORE INSERT OR UPDATE OR DELETE ON kkd.goal_snapshot
FOR EACH ROW EXECUTE FUNCTION kkd.guard_run_data();
CREATE TRIGGER guard_key_result_snapshot BEFORE INSERT OR UPDATE OR DELETE ON kkd.key_result_snapshot
FOR EACH ROW EXECUTE FUNCTION kkd.guard_run_data();
CREATE TRIGGER guard_issue BEFORE INSERT OR UPDATE OR DELETE ON kkd.kkd_issue
FOR EACH ROW EXECUTE FUNCTION kkd.guard_run_data();

CREATE FUNCTION kkd.unit_subtree(p_run_id text, p_unit_id text)
RETURNS TABLE(unit_id text) LANGUAGE sql STABLE AS $$
    WITH RECURSIVE subtree(id) AS (
        SELECT id FROM kkd.unit_snapshot WHERE run_id = p_run_id AND id = p_unit_id
        UNION
        SELECT child.id FROM kkd.unit_snapshot child
        JOIN subtree parent ON child.parent_id = parent.id
        WHERE child.run_id = p_run_id
    )
    SELECT id FROM subtree;
$$;

CREATE VIEW kkd.run_statistics AS
SELECT r.id AS run_id,
    CASE WHEN r.status = 'COMPLETED' THEN COALESCE(s.issues_count, 0) END AS issues_count,
    CASE WHEN r.status = 'COMPLETED' THEN COALESCE(s.critical_count, 0) END AS critical_count,
    CASE WHEN r.status = 'COMPLETED' THEN COALESCE(s.major_count, 0) END AS major_count,
    CASE WHEN r.status = 'COMPLETED' THEN COALESCE(s.minor_count, 0) END AS minor_count
FROM kkd.kkd_run r
LEFT JOIN (
    SELECT run_id, COUNT(*) AS issues_count,
        COUNT(*) FILTER (WHERE severity = 'CRITICAL') AS critical_count,
        COUNT(*) FILTER (WHERE severity = 'MAJOR') AS major_count,
        COUNT(*) FILTER (WHERE severity = 'MINOR') AS minor_count
    FROM kkd.kkd_issue GROUP BY run_id
) s ON s.run_id = r.id;
