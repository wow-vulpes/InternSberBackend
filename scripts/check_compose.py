#!/usr/bin/env python3
"""Check the real deployment in an isolated project; always remove its data."""

import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener


ROOT = Path(__file__).resolve().parents[1]
FIRST = "demo-2026-10-19"
SECOND = "demo-2026-10-26"
HTTP = build_opener(ProxyHandler({}))


def expect(actual, expected, description):
    if actual != expected:
        raise RuntimeError(f"{description}: expected {expected!r}, got {actual!r}")


def main():
    project = "kkd-check-" + secrets.token_hex(6)
    environment = os.environ.copy()
    # Exported local settings must not override the isolated fixture.
    for key in list(environment):
        if key.startswith("COMPOSE_") or key in {
            "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "DB_PORT", "DB_URL",
            "SERVER_PORT", "KKD_DEMO_ENABLED", "KKD_RUN_ENABLED",
        }:
            environment.pop(key)
    with tempfile.TemporaryDirectory(prefix="kkd-compose-") as directory:
        settings = Path(directory) / ".env"
        settings.write_text(
            "DB_NAME=kkd\nDB_USERNAME=kkd\nDB_PORT=0\nSERVER_PORT=0\n"
            "COMPOSE_DEMO_ENABLED=true\nDB_PASSWORD=" + secrets.token_hex(24) + "\n",
            encoding="utf-8",
        )
        settings.chmod(0o600)
        command = [
            "docker", "compose", "--project-name", project,
            "--env-file", str(settings), "-f", str(ROOT / "compose.yaml"),
        ]

        def compose(*arguments, capture=False):
            return subprocess.run(
                command + list(arguments), cwd=ROOT, env=environment, check=True,
                text=True, stdout=subprocess.PIPE if capture else None, timeout=1200,
            ).stdout

        try:
            print(f"Building and starting isolated project {project}", flush=True)
            compose("up", "--build", "-d", "--wait", "--wait-timeout", "180")
            address = compose("port", "app", "8080", capture=True).strip()
            base = "http://" + address

            def get(path, expected_status=200, **parameters):
                url = base + path + ("?" + urlencode(parameters) if parameters else "")
                try:
                    response = HTTP.open(url, timeout=8)
                except HTTPError as error:
                    response = error
                with response:
                    expect(response.code, expected_status, path + " HTTP status")
                    return json.load(response)

            def wait_health(expected_status, expected_value):
                deadline = time.monotonic() + 45
                while True:
                    try:
                        expect(
                            get("/actuator/health/readiness", expected_status),
                            {"status": expected_value}, "readiness",
                        )
                        return
                    except (OSError, URLError, RuntimeError) as error:
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Readiness did not reach expected state") from error
                        time.sleep(1)

            wait_health(200, "UP")
            expect(get("/actuator/health/liveness"), {"status": "UP"}, "liveness")
            runs = get("/api/runs")
            expect([run["id"] for run in runs], [SECOND, FIRST], "demo run order")
            expect([run["issues_count"] for run in runs], [8, 10], "demo counts")
            expect([run["status"] for run in runs], ["completed"] * 2, "demo completion")
            summary = get("/api/kkd/summary", run_id=SECOND, unit_id="U1")
            expect(summary["delta"], {"new": 3, "persisting": 5, "resolved": 5}, "delta")
            expect([metric["value"] for metric in summary["metrics"]], [65.2, 8, 8], "metrics")
            expect([metric["key"] for metric in summary["metrics"]],
                   ["clean_goals_share", "affected_goals_count", "issues_count"], "metric keys")
            expect([metric["unit"] for metric in summary["metrics"]], ["%", "шт.", "шт."], "metric units")
            expect(summary["metrics"][2]["trend"], "better", "improving trend")
            expect(summary["issues_by_severity"], [
                {"code": "critical", "current": 3, "previous": 5, "delta": -2},
                {"code": "major", "current": 2, "previous": 2, "delta": 0},
                {"code": "minor", "current": 3, "previous": 3, "delta": 0},
            ], "severity comparison")
            first_summary = get("/api/kkd/summary", run_id=FIRST, unit_id="U1")
            expect([row["previous"] for row in first_summary["issues_by_severity"]],
                   [None, None, None], "first severity previous")
            expect([row["delta"] for row in first_summary["issues_by_severity"]],
                   [None, None, None], "first severity delta")
            expect(
                first_summary["delta"],
                {"new": None, "persisting": None, "resolved": None}, "first run delta",
            )
            issues = get(
                "/api/kkd/issues", run_id=SECOND, unit_id="U2",
                rule="KR_STALE_PROGRESS", severity="minor", limit=1, offset=1,
            )
            expect(issues["total"], 2, "filtered count")
            expect([item["object_id"] for item in issues["items"]], ["KR11"], "filtered page")
            expect(
                get("/api/kkd/issues", 422, run_id=SECOND, limit=201)["error"]["code"],
                "INVALID_PARAMETER", "validation",
            )
            expect(
                get("/api/kkd/issues", 422, run_id="invalid\x00id")["error"]["code"],
                "INVALID_PARAMETER", "NUL in run identifier",
            )
            expect(
                get("/api/kkd/summary", 422, run_id=SECOND, unit_id="invalid\x00id")["error"]["code"],
                "INVALID_PARAMETER", "NUL in unit identifier",
            )
            get("/actuator/env", 404)
            container = compose("ps", "-q", "app", capture=True).strip()
            metadata = json.loads(subprocess.check_output(
                ["docker", "inspect", container], text=True, timeout=30,
            ))[0]
            expect(metadata["Config"]["User"], "10001:10001", "container user")
            expect(metadata["HostConfig"]["ReadonlyRootfs"], True, "read-only root")
            print("Startup, demo, API, health exposure and container permissions passed", flush=True)

            compose("restart", "app")
            compose("up", "-d", "--wait", "--wait-timeout", "180", "app")
            # Docker may assign a new host port after restarting a port-0 binding.
            base = "http://" + compose("port", "app", "8080", capture=True).strip()
            wait_health(200, "UP")
            expect(get("/api/runs"), runs, "runs after application restart")
            expect(get("/api/kkd/summary", run_id=SECOND, unit_id="U1"), summary, "stable history")
            print("Application restart preserved runs without duplicate demo data", flush=True)

            compose("stop", "db")
            wait_health(503, "DOWN")
            expect(get("/actuator/health/liveness"), {"status": "UP"}, "liveness without DB")
            compose("up", "-d", "--wait", "--wait-timeout", "90", "db")
            wait_health(200, "UP")
            expect(get("/api/runs"), runs, "runs after database recovery")
            print("Database outage and recovery passed; data persisted", flush=True)
        except BaseException:
            subprocess.run(command + ["logs", "--no-color", "--tail", "120"],
                           cwd=ROOT, env=environment, timeout=30, check=False)
            raise
        finally:
            # This project has a random name and never uses the developer's volume.
            compose("down", "--volumes", "--remove-orphans")
            print(f"Removed isolated project {project} and its volume", flush=True)


if __name__ == "__main__":
    main()
