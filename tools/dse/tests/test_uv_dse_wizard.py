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


def experiment_form_fields(
    *,
    index: int = 0,
    name: str = "x",
    kind: str = "bootstrap",
    balance: str = "none",
    output_invoice: bool = False,
) -> dict[str, list[str]]:
    fields = {
        "experiment_count": ["1"],
        f"exp_enabled_{index}": ["on"],
        f"exp_name_{index}": [name],
        f"exp_kind_{index}": [kind],
        f"exp_balance_{index}": [balance],
        f"exp_bal_level_{index}": ["0.5"],
        f"exp_min_delta_{index}": ["10000"],
        f"exp_path_start_{index}": ["pk0"],
        f"exp_path_destination_{index}": ["pk7"],
        f"exp_path_amount_{index}": ["10000"],
        f"exp_path_path_finder_{index}": ["lnd"],
        f"exp_path_topk_{index}": ["5"],
        f"exp_route_sender_{index}": ["pk0"],
        f"exp_route_destination_{index}": ["pk7"],
        f"exp_route_amount_{index}": ["10000"],
        f"exp_route_path_finder_{index}": ["lnd"],
        f"exp_route_max_fees_{index}": ["1000"],
        f"exp_route_message_{index}": [""],
        f"exp_inv_node_events_per_block_{index}": ["0.08"],
        f"exp_inv_blocks_{index}": ["4"],
        f"exp_inv_min_amt_{index}": ["50000"],
        f"exp_inv_max_amt_{index}": ["100000"],
        f"exp_inv_max_fees_{index}": ["1000"],
        f"exp_inv_path_finder_{index}": ["lnd"],
        f"exp_output_network_{index}": ["on"],
    }
    if output_invoice:
        fields[f"exp_output_invoice_{index}"] = ["on"]
    return fields


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
    def test_quoted_comma_valued_property_is_one_dse_value(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "space.json"
            form = {
                "token": [wizard.SESSION_TOKEN],
                "parameter_count": ["1"],
                "name_0": ["base_fee_set"],
                "include_0": ["on"],
                "values_0": ['"0,100,1000"'],
                "save_path": [str(output)],
            }
            form.update(experiment_form_fields())

            save_path, payload = wizard.save_dse_from_form(form)

        self.assertEqual(save_path.resolve(), output.resolve())
        self.assertEqual(payload["parameters"]["base_fee_set"], ["0,100,1000"])

    def test_unquoted_commas_build_cartesian_values(self) -> None:
        values = wizard.parse_json_values("1, 7")

        self.assertEqual(values, [1, 7])

    def test_default_text_quotes_comma_valued_properties(self) -> None:
        self.assertEqual(wizard.default_value_text("0,100,1000"), '"0,100,1000"')

    def test_malformed_quoted_values_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Invalid DSE value list"):
            wizard.parse_json_values('"0,100,1000", bad"quote')

    def test_failed_save_state_preserves_user_inputs(self) -> None:
        form = {
            "properties_path": [wizard.DEFAULT_PROPERTIES],
            "dse_json_path": [wizard.DEFAULT_DSE_JSON],
            "save_path": ["dse_runs/tutorial/bad.json"],
            "parameter_count": ["1"],
            "name_0": ["seed"],
            "include_0": ["on"],
            "values_0": ["1, 7"],
        }
        form.update(experiment_form_fields(kind="invoice", output_invoice=True))

        state = wizard.create_state_from_form(form, error="Invalid parameter values")

        self.assertEqual(state.value_text_overrides["seed"], "1, 7")
        self.assertEqual(state.experiment_rows[0]["kind"], "invoice")
        self.assertEqual(state.experiments[0]["name"], "x")

    def test_malformed_form_count_does_not_break_error_state(self) -> None:
        form = {
            "parameter_count": ["not-an-int"],
        }
        form.update(experiment_form_fields())

        with self.assertRaisesRegex(ValueError, "Invalid parameter count"):
            wizard.save_dse_from_form(form)

        state = wizard.create_state_from_form(form, error="Invalid parameter count")

        self.assertEqual(state.selected_parameters, {})
        self.assertEqual(state.experiments[0]["name"], "x")

    def test_default_create_state_loads_prefilled_dse_json(self) -> None:
        state = wizard.default_create_state({})

        self.assertEqual(state.dse_json_path, wizard.DEFAULT_DSE_JSON)
        self.assertEqual(state.save_path, wizard.DEFAULT_SAVE_JSON)
        self.assertIn("seed", state.selected_parameters)
        self.assertTrue(state.experiments)
        self.assertNotEqual(state.save_path, state.dse_json_path)

    def test_experiment_form_builds_invoice_experiment(self) -> None:
        experiment = wizard.build_experiments_from_form(experiment_form_fields(kind="invoice", output_invoice=True))[0]

        self.assertEqual(experiment["name"], "x")
        self.assertEqual(experiment["commands"][0], "boot")
        self.assertEqual(experiment["commands"][-1]["command"], "inv")
        self.assertEqual(experiment["outputs"], ["network", "invoice"])

    def test_create_page_uses_file_selectors_and_preview(self) -> None:
        state = wizard.WizardState(
            properties_path=wizard.DEFAULT_PROPERTIES,
            dse_json_path=wizard.DEFAULT_DSE_JSON,
            save_path=wizard.DEFAULT_SAVE_JSON,
            parameters={"seed": "1", "base_fee_set": "0,100,1000"},
            selected_parameters={"seed": [1, 7]},
            experiments=[{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
            value_text_overrides={},
        )

        html = wizard.render_create({}, state=state).decode("utf-8")

        self.assertIn('data-browse-action="/create/load"', html)
        self.assertIn('data-browse-action="/create/save"', html)
        self.assertIn('id="refresh-json-preview"', html)
        self.assertIn('id="dse-json-preview"', html)
        self.assertIn("Experiments section of the DSE JSON", html)
        self.assertIn("Use experiment", html)
        self.assertIn("Recipe", html)
        self.assertIn("Bootstrap command", html)
        self.assertIn("Balance command", html)
        self.assertIn("Path finding command", html)
        self.assertIn("Invoice campaign command", html)
        self.assertIn("&quot;0,100,1000&quot;", html)
        self.assertNotIn("Experiments JSON array", html)
        self.assertNotIn('id="experiments_json"', html)
        self.assertNotIn('formaction="/create/load"', html)
        self.assertNotIn(">Load JSON</button>", html)
        self.assertNotIn(">Save JSON</button>", html)
        self.assertNotIn("Value mode", html)

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
