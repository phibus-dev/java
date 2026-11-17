import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[3]
CLI = ROOT / "bin" / "evo-snt"
RUN_ID = "11111111-2222-4333-8444-555555555555"
PROFILE_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
AGENT_ID = "99999999-8888-4777-8666-555555555555"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def _handle(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        status, headers, response = self.server.callback(self.command, self.path, body, self.headers)
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *_args):
        return


class EvoSntCliTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.callback = self._unexpected
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.callback = lambda *args: self.callback(*args)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def run_cli(self, *arguments):
        return subprocess.run(
            [sys.executable, str(CLI), "--url", self.url, "--token", "test-token", *arguments],
            cwd=ROOT, text=True, capture_output=True, timeout=10, check=False,
        )

    def test_lists_safe_profiles_with_bearer_authentication(self):
        def callback(method, path, body, headers):
            self.requests.append((method, path, body, headers.get("Authorization")))
            response = [{"id": PROFILE_ID, "name": "primary", "defaultProfile": True}]
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("profiles", "list", "--type", "kafka")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/api/v1/profiles/kafka", self.requests[0][1])
        self.assertIn(PROFILE_ID, result.stdout)
        self.assertIn("primary", result.stdout)
        self.assertEqual("Bearer test-token", self.requests[0][3])

    def test_starts_with_idempotency_key_and_reports_replay(self):
        def callback(method, path, body, headers):
            self.requests.append((method, path, json.loads(body), headers.get("Idempotency-Key")))
            response = {"runId": RUN_ID, "testType": "S3", "scope": "LOCAL",
                        "monitorUrl": f"/api/v1/runs/{RUN_ID}", "replayed": True}
            return 202, {"Content-Type": "application/json", "Idempotency-Replayed": "true"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("runs", "start", "--profile", PROFILE_ID,
                              "--idempotency-key", "pipeline-42")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(RUN_ID, result.stdout.strip())
        self.assertIn("replayed", result.stderr.lower())
        self.assertEqual("pipeline-42", self.requests[0][3])
        self.assertEqual(PROFILE_ID, self.requests[0][2]["profileId"])

    def test_starts_clickhouse_and_distributed_kafka(self):
        received = []

        def callback(method, path, body, headers):
            payload = json.loads(body)
            received.append((method, path, payload, headers.get("Idempotency-Key")))
            response = {"runId": RUN_ID, "testType": "CLICKHOUSE", "scope": "LOCAL",
                        "monitorUrl": f"/api/v1/runs/{RUN_ID}", "replayed": False}
            return 202, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        clickhouse = self.run_cli(
            "runs", "start", "clickhouse", "--profile", PROFILE_ID,
            "--idempotency-key", "clickhouse-1", "--table", "events",
            "--clickhouse-operation", "SELECT", "--query-count", "20",
        )
        distributed = self.run_cli(
            "runs", "start", "kafka", "--scope", "distributed",
            "--profile", PROFILE_ID, "--agents", AGENT_ID,
            "--idempotency-key", "kafka-1", "--topic", "events",
        )

        self.assertEqual(0, clickhouse.returncode, clickhouse.stderr)
        self.assertEqual(0, distributed.returncode, distributed.stderr)
        self.assertEqual("/api/v1/runs/clickhouse", received[0][1])
        self.assertEqual("SELECT", received[0][2]["operation"])
        self.assertEqual("/api/v1/runs/distributed/kafka", received[1][1])
        self.assertEqual([AGENT_ID], received[1][2]["agentIds"])
        self.assertEqual("events", received[1][2]["testRequest"]["topic"])

    def test_waits_until_terminal_status(self):
        statuses = iter(("RUNNING", "COMPLETED"))

        def callback(_method, _path, _body, _headers):
            response = {"id": RUN_ID, "status": next(statuses)}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("runs", "wait", RUN_ID, "--timeout", "2", "--interval", "0.01")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("COMPLETED", result.stdout.strip())
        self.assertIn("RUNNING", result.stderr)

    def test_wait_falls_back_to_persisted_history_after_restart(self):
        def callback(_method, path, _body, _headers):
            if path == f"/api/v1/runs/{RUN_ID}":
                problem = {"code": "RUN_NOT_FOUND", "detail": "not in runtime"}
                return 404, {"Content-Type": "application/problem+json"}, json.dumps(problem).encode()
            self.assertEqual(f"/api/v1/run-history/{RUN_ID}", path)
            response = {"id": RUN_ID, "status": "COMPLETED"}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("runs", "wait", RUN_ID, "--timeout", "2", "--interval", "0.01")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("COMPLETED", result.stdout.strip())

    def test_exports_history_json_without_overwriting_by_default(self):
        def callback(_method, path, _body, _headers):
            self.assertEqual(f"/api/v1/run-history/{RUN_ID}/export/json", path)
            response = {"id": RUN_ID, "status": "COMPLETED"}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            result = self.run_cli("runs", "export", RUN_ID, "--format", "json", "-o", str(output))
            second = self.run_cli("runs", "export", RUN_ID, "--format", "json", "-o", str(output))

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(RUN_ID, json.loads(output.read_text())["id"])
            self.assertEqual(2, second.returncode)
            self.assertIn("File exists", second.stderr)

    def test_exports_csv_through_the_unified_endpoint(self):
        def callback(_method, path, _body, _headers):
            self.assertEqual(f"/api/v1/run-history/{RUN_ID}/export/csv", path)
            return 200, {"Content-Type": "text/csv"}, b'"id","testType"\\n'

        self.callback = callback
        result = self.run_cli("runs", "export", RUN_ID, "--format", "csv", "-o", "-")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('"id","testType"', result.stdout)

    def test_quality_gate_posts_policy_baseline_and_overrides(self):
        baseline_id = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"

        def callback(method, path, body, _headers):
            self.requests.append((method, path, json.loads(body)))
            response = {"runId": RUN_ID, "policyId": "BASELINE_TOLERANT", "verdict": "PASS",
                        "criteria": [{"id": "p95LatencyMsVsBaseline", "passed": True}]}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("runs", "assert", RUN_ID, "--policy", "BASELINE_TOLERANT",
                              "--baseline", baseline_id, "--max-p95-ms", "750",
                              "--min-participants", "2", "--json")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("POST", self.requests[0][0])
        self.assertEqual(f"/api/v1/run-history/{RUN_ID}/quality-gate", self.requests[0][1])
        self.assertEqual(baseline_id, self.requests[0][2]["baselineRunId"])
        self.assertEqual(750.0, self.requests[0][2]["overrides"]["maxP95LatencyMs"])
        self.assertEqual(2, self.requests[0][2]["overrides"]["minParticipants"])
        self.assertEqual("PASS", json.loads(result.stdout)["verdict"])

    def test_quality_gate_returns_exit_six_and_prints_failed_criteria(self):
        def callback(_method, _path, _body, _headers):
            response = {"runId": RUN_ID, "policyId": "SMOKE", "verdict": "FAIL",
                        "criteria": [{"id": "errorRatePercent", "passed": False,
                                      "actual": "2.000", "operator": "<=",
                                      "expected": "0.000", "unit": "%"}]}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        result = self.run_cli("runs", "assert", RUN_ID)

        self.assertEqual(6, result.returncode)
        self.assertIn("FAIL", result.stdout)
        self.assertIn("errorRatePercent", result.stderr)

    def test_soak_runs_repeated_scenario_and_writes_complete_evidence(self):
        run_ids = [
            "10000000-0000-4000-8000-000000000001",
            "10000000-0000-4000-8000-000000000002",
            "10000000-0000-4000-8000-000000000003",
        ]
        starts = []

        def callback(method, path, body, headers):
            if method == "GET" and path == "/api/v1/benchmark-scenarios/S3_MIXED_STEADY":
                response = {
                    "id": "S3_MIXED_STEADY", "name": "S3 mixed steady load", "testType": "S3",
                    "supportedScopes": ["LOCAL", "DISTRIBUTED"],
                    "parameters": {"operation": "MIXED", "executionMode": "TIME_DURATION",
                                   "durationSeconds": 600, "warmupSeconds": 60},
                }
            elif method == "POST" and path == "/api/v1/runs/s3":
                payload = json.loads(body)
                starts.append((payload, headers.get("Idempotency-Key")))
                response = {"runId": run_ids[len(starts) - 1], "testType": "S3",
                            "scope": "LOCAL", "replayed": False}
            elif method == "GET" and path.startswith("/api/v1/runs/"):
                response = {"id": path.rsplit("/", 1)[-1], "status": "COMPLETED"}
            elif method == "GET" and path.endswith("/export/json"):
                run_id = path.split("/")[4]
                index = run_ids.index(run_id)
                response = {"id": run_id, "status": "COMPLETED",
                            "operationsPerSecond": [100, 96, 92][index],
                            "throughputMiBps": [50, 49, 47][index],
                            "p95LatencyMs": [100, 105, 110][index],
                            "p99LatencyMs": 150, "completedOperations": 1000, "errors": 0}
            elif method == "POST" and path.endswith("/quality-gate"):
                response = {"runId": path.split("/")[4], "policyId": "SMOKE",
                            "verdict": "PASS", "criteria": []}
            else:
                raise AssertionError(f"Unexpected request: {method} {path}")
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "soak.json"
            output = root / "evidence"
            config.write_text(json.dumps({
                "version": 1, "scenarioId": "S3_MIXED_STEADY", "scope": "LOCAL",
                "profileId": PROFILE_ID, "iterations": 3, "cooldownSeconds": 0,
                "waitTimeoutSeconds": 1, "pollIntervalSeconds": 0.01,
                "policyId": "SMOKE", "idempotencyPrefix": "test-soak-42",
                "workloadOverrides": {"objectKey": "performance-test/soak.bin"},
            }))

            result = self.run_cli("runs", "soak", "--config", str(config),
                                  "--output-dir", str(output), "--json")

            self.assertEqual(0, result.returncode, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual("PASS", summary["verdict"])
            self.assertEqual(3, summary["passedWindows"])
            self.assertEqual("PASS", summary["drift"]["verdict"])
            self.assertEqual(PROFILE_ID, starts[0][0]["profileId"])
            self.assertEqual("performance-test/soak.bin", starts[0][0]["objectKey"])
            self.assertEqual("MIXED", starts[0][0]["operation"])
            self.assertEqual("test-soak-42-001", starts[0][1])
            self.assertTrue((output / "soak-summary.md").is_file())
            self.assertTrue((output / "window-003" / "quality-gate.json").is_file())

    def test_soak_returns_exit_seven_when_long_term_drift_fails(self):
        run_ids = [
            "20000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000002",
            "20000000-0000-4000-8000-000000000003",
        ]
        start_count = 0
        distributed_requests = []

        def callback(method, path, body, _headers):
            nonlocal start_count
            if method == "GET" and path == "/api/v1/benchmark-scenarios/KAFKA_PRODUCER_THROUGHPUT":
                response = {"id": "KAFKA_PRODUCER_THROUGHPUT", "name": "Kafka throughput",
                            "testType": "KAFKA", "supportedScopes": ["LOCAL", "DISTRIBUTED"],
                            "parameters": {"messageCount": 1000, "messageSizeBytes": 1024,
                                           "producerThreads": 2}}
            elif method == "POST" and path == "/api/v1/runs/distributed/kafka":
                distributed_requests.append(json.loads(body))
                response = {"runId": run_ids[start_count], "scope": "DISTRIBUTED"}
                start_count += 1
            elif method == "GET" and path.startswith("/api/v1/runs/"):
                response = {"id": path.rsplit("/", 1)[-1], "status": "COMPLETED"}
            elif method == "GET" and path.endswith("/export/json"):
                run_id = path.split("/")[4]
                index = run_ids.index(run_id)
                response = {"id": run_id, "status": "COMPLETED",
                            "operationsPerSecond": [100, 90, 40][index],
                            "throughputMiBps": [10, 9, 4][index],
                            "p95LatencyMs": [100, 120, 200][index],
                            "completedOperations": 1000, "errors": 0}
            elif method == "POST" and path.endswith("/quality-gate"):
                response = {"verdict": "PASS", "policyId": "SMOKE", "criteria": []}
            else:
                raise AssertionError(f"Unexpected request: {method} {path}")
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "soak.json"
            output = root / "evidence"
            config.write_text(json.dumps({
                "version": 1, "scenarioId": "KAFKA_PRODUCER_THROUGHPUT",
                "scope": "DISTRIBUTED", "profileId": PROFILE_ID, "agentIds": [AGENT_ID],
                "iterations": 3, "cooldownSeconds": 0,
                "waitTimeoutSeconds": 1, "pollIntervalSeconds": 0.01,
                "idempotencyPrefix": "drift-soak-42", "policyId": "SMOKE",
                "workloadOverrides": {"topic": "performance-events"},
            }))

            result = self.run_cli("runs", "soak", "--config", str(config),
                                  "--output-dir", str(output), "--json")

            self.assertEqual(7, result.returncode, result.stderr)
            summary = json.loads(result.stdout)
            self.assertEqual("FAIL", summary["verdict"])
            failed = {item["id"] for item in summary["drift"]["criteria"] if not item["passed"]}
            self.assertIn("operationsDropPercent", failed)
            self.assertIn("throughputDropPercent", failed)
            self.assertIn("p95IncreasePercent", failed)
            self.assertEqual([AGENT_ID], distributed_requests[0]["agentIds"])
            self.assertEqual(PROFILE_ID, distributed_requests[0]["testRequest"]["profileId"])
            self.assertEqual("performance-events", distributed_requests[0]["testRequest"]["topic"])

    def test_soak_rejects_credentials_before_calling_the_api(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "soak.json"
            config.write_text(json.dumps({
                "version": 1, "scenarioId": "S3_MIXED_STEADY", "scope": "LOCAL",
                "profileId": PROFILE_ID, "iterations": 3,
                "idempotencyPrefix": "unsafe-soak",
                "workloadOverrides": {"secretKey": "must-not-be-here"},
            }))

            result = self.run_cli("runs", "soak", "--config", str(config),
                                  "--output-dir", str(Path(directory) / "evidence"))

            self.assertEqual(2, result.returncode)
            self.assertIn("Sensitive field is not allowed", result.stderr)

    def test_soak_preserves_partial_summary_on_api_error(self):
        def callback(method, path, _body, _headers):
            if method == "GET" and path == "/api/v1/benchmark-scenarios/S3_UPLOAD_THROUGHPUT":
                response = {"id": "S3_UPLOAD_THROUGHPUT", "name": "S3 upload",
                            "testType": "S3", "supportedScopes": ["LOCAL"],
                            "parameters": {"operation": "UPLOAD", "objectCount": 1}}
                return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()
            problem = {"code": "CAPACITY_EXHAUSTED", "detail": "reference environment is busy",
                       "requestId": "soak-request-42"}
            return 503, {"Content-Type": "application/problem+json"}, json.dumps(problem).encode()

        self.callback = callback
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "soak.json"
            output = root / "evidence"
            config.write_text(json.dumps({
                "version": 1, "scenarioId": "S3_UPLOAD_THROUGHPUT", "scope": "LOCAL",
                "profileId": PROFILE_ID, "iterations": 3, "cooldownSeconds": 0,
                "waitTimeoutSeconds": 1, "pollIntervalSeconds": 0.01,
                "idempotencyPrefix": "api-error-soak", "policyId": "SMOKE",
            }))

            result = self.run_cli("runs", "soak", "--config", str(config),
                                  "--output-dir", str(output), "--json")

            self.assertEqual(3, result.returncode)
            self.assertIn("CAPACITY_EXHAUSTED", result.stderr)
            summary = json.loads((output / "soak-summary.json").read_text())
            self.assertEqual("ERROR", summary["verdict"])
            self.assertEqual("soak-request-42", summary["technicalError"]["requestId"])
            self.assertTrue((output / "window-001" / "window.json").is_file())

    def test_lists_and_shows_benchmark_scenarios(self):
        def callback(_method, path, _body, _headers):
            if path == "/api/v1/benchmark-scenarios?testType=KAFKA":
                response = [{"id": "KAFKA_PRODUCER_THROUGHPUT", "testType": "KAFKA",
                             "recommendedPolicyId": "BASELINE_TOLERANT", "name": "Kafka throughput"}]
            else:
                self.assertEqual("/api/v1/benchmark-scenarios/KAFKA_PRODUCER_THROUGHPUT", path)
                response = {"id": "KAFKA_PRODUCER_THROUGHPUT", "name": "Kafka throughput",
                            "description": "Stable producer load", "parameters": {"producerThreads": 8}}
            return 200, {"Content-Type": "application/json"}, json.dumps(response).encode()

        self.callback = callback
        listed = self.run_cli("benchmarks", "list", "--type", "kafka")
        shown = self.run_cli("benchmarks", "show", "KAFKA_PRODUCER_THROUGHPUT", "--json")

        self.assertEqual(0, listed.returncode, listed.stderr)
        self.assertIn("KAFKA_PRODUCER_THROUGHPUT", listed.stdout)
        self.assertEqual(0, shown.returncode, shown.stderr)
        self.assertEqual(8, json.loads(shown.stdout)["parameters"]["producerThreads"])

    def test_prints_machine_error_code_and_request_id(self):
        def callback(_method, _path, _body, _headers):
            problem = {"code": "IDEMPOTENCY_CONFLICT", "detail": "different request",
                       "requestId": "request-42"}
            return 409, {"Content-Type": "application/problem+json"}, json.dumps(problem).encode()

        self.callback = callback
        result = self.run_cli("runs", "start", "--profile", PROFILE_ID,
                              "--idempotency-key", "pipeline-42")

        self.assertEqual(3, result.returncode)
        self.assertIn("IDEMPOTENCY_CONFLICT", result.stderr)
        self.assertIn("request-42", result.stderr)

    def test_rejects_invalid_identifiers_without_sending_a_request(self):
        result = self.run_cli("runs", "start", "--profile", "not-a-uuid",
                              "--idempotency-key", "pipeline-42")
        wait_result = self.run_cli("runs", "wait", "not-a-uuid", "--timeout", "1")

        self.assertEqual(2, result.returncode)
        self.assertIn("--profile must be a UUID", result.stderr)
        self.assertEqual(2, wait_result.returncode)
        self.assertIn("run_id must be a UUID", wait_result.stderr)

    def _unexpected(self, method, path, _body, _headers):
        raise AssertionError(f"Unexpected request: {method} {path}")


if __name__ == "__main__":
    unittest.main()
