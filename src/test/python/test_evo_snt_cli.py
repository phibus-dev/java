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
        result = self.run_cli("profiles", "list")

        self.assertEqual(0, result.returncode, result.stderr)
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
            self.assertEqual(f"/api/v1/run-history/{RUN_ID}", path)
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
