import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
RUNNER = ROOT / "scripts" / "reference-soak-local.sh"


class ReferenceSoakRunnerTest(unittest.TestCase):
    def test_passes_credential_path_and_separate_timestamped_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_home = root / "app"
            cli = app_home / "bin" / "evo-snt"
            cli.parent.mkdir(parents=True)
            cli.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\"\n",
                encoding="utf-8",
            )
            cli.chmod(0o755)
            config = root / "soak.json"
            config.write_text("{}\n", encoding="utf-8")
            token_file = root / "api-token"
            token_file.write_text("top-secret-token\n", encoding="utf-8")
            private_root = root / "private"
            public_root = root / "public"
            env = os.environ.copy()
            env.update({
                "EVO_SNT_HOME": str(app_home),
                "EVO_SNT_CONFIG": str(config),
                "EVO_SNT_EVIDENCE_ROOT": str(private_root),
                "EVO_SNT_PUBLIC_ROOT": str(public_root),
                "EVO_SNT_TOKEN_FILE": str(token_file),
                "EVO_SNT_URL": "https://coordinator.internal.example",
                "EVO_SNT_IDEMPOTENCY_PREFIX": "offline-test",
            })

            result = subprocess.run(
                [str(RUNNER)], env=env, cwd=ROOT, text=True, capture_output=True,
                timeout=10, check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(str(token_file), result.stdout)
            self.assertIn("offline-test", result.stdout)
            self.assertNotIn("top-secret-token", result.stdout + result.stderr)
            private_runs = list(private_root.iterdir())
            public_runs = list(public_root.iterdir())
            self.assertEqual(1, len(private_runs))
            self.assertEqual(1, len(public_runs))
            self.assertEqual(private_runs[0].name, public_runs[0].name)
            self.assertNotEqual(private_runs[0].resolve(), public_runs[0].resolve())


if __name__ == "__main__":
    unittest.main()
