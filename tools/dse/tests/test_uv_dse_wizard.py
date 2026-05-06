from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOLS_DSE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DSE))

import uv_dse_common as common
import uv_dse_wizard as wizard


class DseCommonTest(unittest.TestCase):
    def test_properties_parser_handles_includes_escapes_and_continuations(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "included.properties").write_text("escaped\\ key: hello\\ world\n", encoding="utf-8")
            (root / "base.properties").write_text(
                "@include=included.properties\n"
                "continued=value\\\n"
                "  tail\n"
                "unicode=pk\\u0031\n",
                encoding="utf-8",
            )

            parsed = common.load_properties_with_includes(root / "base.properties")

        self.assertEqual(parsed["escaped key"], "hello world")
        self.assertEqual(parsed["continued"], "valuetail")
        self.assertEqual(parsed["unicode"], "pk1")

    def test_dse_payload_rejects_invalid_parameter_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "must provide a non-empty JSON array"):
            common.validate_dse_payload(
                {
                    "parameters": {"seed": 1},
                    "experiments": [{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
                }
            )

    def test_experiment_validation_matches_runner_commands_and_outputs(self) -> None:
        payload = common.validate_dse_payload(
            {
                "parameters": {"seed": [1]},
                "experiments": [{"name": "x", "commands": ["BOOT"], "outputs": ["stat", "invoice_report"]}],
            }
        )

        self.assertEqual(payload["experiments"][0]["outputs"], ["network", "invoice"])

        with self.assertRaisesRegex(ValueError, "unsupported command"):
            common.validate_dse_payload(
                {
                    "parameters": {"seed": [1]},
                    "experiments": [{"name": "x", "commands": ["unknown"], "outputs": ["network"]}],
                }
            )


class DseWizardCreateTest(unittest.TestCase):
    def test_single_mode_preserves_comma_valued_property(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "space.json"
            form = {
                "token": [wizard.SESSION_TOKEN],
                "parameter_count": ["1"],
                "name_0": ["base_fee_set"],
                "include_0": ["on"],
                "mode_0": ["single"],
                "values_0": ["0,100,1000"],
                "experiments_json": [json.dumps([{"name": "x", "commands": ["boot"], "outputs": ["network"]}])],
                "save_path": [str(output)],
            }

            save_path, payload = wizard.save_dse_from_form(form)

        self.assertEqual(save_path.resolve(), output.resolve())
        self.assertEqual(payload["parameters"]["base_fee_set"], ["0,100,1000"])

    def test_list_mode_builds_cartesian_values(self) -> None:
        form = {
            "parameter_count": ["1"],
            "name_0": ["seed"],
            "include_0": ["on"],
            "mode_0": ["list"],
            "values_0": ["1, 7"],
        }

        values = wizard.parse_parameter_values(form["values_0"][0], form["mode_0"][0])

        self.assertEqual(values, [1, 7])

    def test_failed_save_state_preserves_user_inputs(self) -> None:
        form = {
            "properties_path": [wizard.DEFAULT_PROPERTIES],
            "dse_json_path": [wizard.DEFAULT_DSE_JSON],
            "save_path": ["dse_runs/tutorial/bad.json"],
            "parameter_count": ["1"],
            "name_0": ["seed"],
            "include_0": ["on"],
            "mode_0": ["list"],
            "values_0": ["1, 7"],
            "experiments_json": ["not-json"],
        }

        state = wizard.create_state_from_form(form, error="Invalid experiments JSON")

        self.assertEqual(state.value_text_overrides["seed"], "1, 7")
        self.assertEqual(state.value_modes["seed"], "list")
        self.assertEqual(state.experiments_text, "not-json")

    def test_malformed_form_count_does_not_break_error_state(self) -> None:
        form = {
            "parameter_count": ["not-an-int"],
            "experiments_json": ["not-json"],
        }

        with self.assertRaisesRegex(ValueError, "Invalid parameter count"):
            wizard.save_dse_from_form(form)

        state = wizard.create_state_from_form(form, error="Invalid parameter count")

        self.assertEqual(state.selected_parameters, {})
        self.assertEqual(state.experiments_text, "not-json")

    def test_load_dse_json_rejects_invalid_parameter_shape(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.json"
            path.write_text(
                json.dumps(
                    {
                        "parameters": {"seed": 1},
                        "experiments": [{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "must provide a non-empty JSON array"):
                wizard.load_dse_json(path)


class DseWizardRunTest(unittest.TestCase):
    def test_build_dse_command_requires_force_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            properties = root / "base.properties"
            dse_json = root / "space.json"
            output = root / "output"
            properties.write_text("seed=1\n", encoding="utf-8")
            dse_json.write_text(
                json.dumps(
                    {
                        "parameters": {"seed": [1]},
                        "experiments": [{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
                    }
                ),
                encoding="utf-8",
            )
            output.mkdir()
            form = {
                "base_properties": [str(properties)],
                "dse_json": [str(dse_json)],
                "output_dir": [str(output)],
                "force": ["on"],
            }

            with self.assertRaisesRegex(ValueError, "Confirm replacement"):
                wizard.build_dse_command(form)

            form["force_confirm"] = ["on"]
            command, _query = wizard.build_dse_command(form)

        self.assertIn("--force", command)

    def test_build_dse_command_refuses_existing_output_without_force(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            properties = root / "base.properties"
            dse_json = root / "space.json"
            output = root / "output"
            properties.write_text("seed=1\n", encoding="utf-8")
            dse_json.write_text(
                json.dumps(
                    {
                        "parameters": {"seed": [1]},
                        "experiments": [{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
                    }
                ),
                encoding="utf-8",
            )
            output.mkdir()

            with self.assertRaisesRegex(ValueError, "already exists"):
                wizard.build_dse_command(
                    {
                        "base_properties": [str(properties)],
                        "dse_json": [str(dse_json)],
                        "output_dir": [str(output)],
                    }
                )

    def test_background_job_collects_output(self) -> None:
        job = wizard.RunJob(
            job_id="test",
            command=[sys.executable, "-c", "print('ok')"],
            query={},
            started_at=0.0,
            lock=wizard.threading.Lock(),
        )

        wizard.run_job_worker(job)

        self.assertEqual(job.status, "completed")
        self.assertEqual(job.return_code, 0)
        self.assertIn("ok", job.output)

    def test_cancel_run_job_terminates_process_group(self) -> None:
        process = mock.Mock()
        process.pid = 12345
        process.poll.return_value = None
        job = wizard.RunJob(
            job_id="cancel-me",
            command=[],
            query={},
            process=process,
            lock=wizard.threading.Lock(),
        )
        with wizard.RUN_JOBS_LOCK:
            wizard.RUN_JOBS[job.job_id] = job

        try:
            with mock.patch.object(wizard.os, "killpg") as killpg:
                self.assertTrue(wizard.cancel_run_job(job.job_id))
                killpg.assert_called_once_with(process.pid, wizard.signal.SIGTERM)
        finally:
            with wizard.RUN_JOBS_LOCK:
                wizard.RUN_JOBS.pop(job.job_id, None)

        self.assertEqual(job.status, "cancelled")


class DseWizardSecurityAndNativeSelectorTest(unittest.TestCase):
    def test_token_validation_rejects_missing_tokens(self) -> None:
        with self.assertRaises(PermissionError):
            wizard.require_token({})

    def test_linux_native_selector_diagnostic_without_dialog_tool(self) -> None:
        with mock.patch.object(wizard.sys, "platform", "linux"), mock.patch.object(wizard.shutil, "which", return_value=None):
            self.assertIn("zenity", wizard.native_selector_diagnostic())

    def test_token_validation_accepts_session_token(self) -> None:
        wizard.require_token({"token": [wizard.SESSION_TOKEN]})


if __name__ == "__main__":
    unittest.main()
