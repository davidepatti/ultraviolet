from __future__ import annotations

import importlib.machinery
import importlib.util
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


def load_dse_run_module():
    loader = importlib.machinery.SourceFileLoader("uv_dse_run", str(TOOLS_DSE / "uv_dse_run"))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    if spec is None:
        raise RuntimeError("Could not load uv_dse_run module")
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def experiment_form_fields(
    *,
    index: int = 0,
    name: str = "x",
    kind: str = "bootstrap",
    balance: str = "none",
    output_invoice: bool = False,
) -> dict[str, list[str]]:
    fields = {
        f"exp_name_{index}": [name],
        f"exp_kind_{index}": [kind],
        f"exp_boot_mode_{index}": ["scratch"],
        f"exp_boot_file_{index}": [""],
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
                    "experiment": {"name": "x", "commands": ["boot"], "outputs": ["network"]},
                }
            )

    def test_experiment_validation_matches_runner_commands_and_outputs(self) -> None:
        payload = common.validate_dse_payload(
            {
                "parameters": {"seed": [1]},
                "experiment": {"name": "x", "commands": ["BOOT"], "outputs": ["stat", "invoice_report"]},
            }
        )

        self.assertEqual(payload["experiment"]["outputs"], ["network", "invoice"])

        with self.assertRaisesRegex(ValueError, "unsupported command"):
            common.validate_dse_payload(
                {
                    "parameters": {"seed": [1]},
                    "experiment": {"name": "x", "commands": ["unknown"], "outputs": ["network"]},
                }
            )

    def test_experiment_validation_accepts_boot_snapshot_object(self) -> None:
        payload = common.validate_dse_payload(
            {
                "parameters": {"seed": [1]},
                "experiment": {
                    "name": "load_snapshot",
                    "commands": [{"command": "boot", "mode": "load", "file": "snapshots/base.dat"}],
                    "outputs": ["network"],
                },
            }
        )

        self.assertEqual(payload["experiment"]["commands"][0]["command"], "boot")
        self.assertEqual(payload["experiment"]["commands"][0]["mode"], "load")

    def test_experiment_validation_rejects_experiments_array(self) -> None:
        with self.assertRaisesRegex(ValueError, "not an 'experiments' array"):
            common.validate_dse_payload(
                {
                    "parameters": {"seed": [1]},
                    "experiments": [{"name": "x", "commands": ["boot"], "outputs": ["network"]}],
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
                "dse_json_path": [str(output)],
            }
            form.update(experiment_form_fields())

            output_path, payload = wizard.save_dse_from_form(form)

        self.assertEqual(output_path.resolve(), output.resolve())
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
            "dse_json_path": ["dse_runs/tutorial/bad.json"],
            "parameter_count": ["1"],
            "name_0": ["seed"],
            "include_0": ["on"],
            "values_0": ["1, 7"],
        }
        form.update(experiment_form_fields(kind="invoice", output_invoice=True))

        state = wizard.create_state_from_form(form, error="Invalid parameter values")

        self.assertEqual(state.value_text_overrides["seed"], "1, 7")
        self.assertEqual(state.experiment_row["kind"], "invoice")
        self.assertEqual(state.experiment["name"], "x")

    def test_malformed_form_count_does_not_break_error_state(self) -> None:
        form = {
            "parameter_count": ["not-an-int"],
        }
        form.update(experiment_form_fields())

        with self.assertRaisesRegex(ValueError, "Invalid parameter count"):
            wizard.save_dse_from_form(form)

        state = wizard.create_state_from_form(form, error="Invalid parameter count")

        self.assertEqual(state.selected_parameters, {})
        self.assertEqual(state.experiment["name"], "x")

    def test_default_create_state_loads_prefilled_dse_json(self) -> None:
        state = wizard.default_create_state({})

        self.assertEqual(state.dse_json_path, wizard.DEFAULT_DSE_JSON)
        self.assertIn("seed", state.selected_parameters)
        self.assertEqual(state.experiment["name"], "quick_invoice_hops")
        self.assertEqual(state.selected_parameters["bootstrap_nodes"], [100])
        self.assertEqual(state.selected_parameters["bootstrap_blocks"], [100])
        self.assertEqual(state.selected_parameters["blocktime_ms"], [100])
        self.assertEqual(state.selected_parameters["node_services_tick_ms"], [10])
        self.assertEqual(state.selected_parameters["gossip_flush_period_ms"], [10])
        self.assertEqual(state.selected_parameters["p2p_max_age"], [10])
        self.assertEqual(state.selected_parameters["gossip_flush_size"], [500])
        self.assertEqual(state.selected_parameters["pathfinding_max_hops"], [3, 6])
        self.assertEqual(state.selected_parameters["seed"], [1, 7])

    def test_startup_alignment_keeps_template_values_in_parameter_space(self) -> None:
        aligned = wizard.align_default_parameter_space_with_properties(
            {"single": [1], "multi_missing": [1, 2], "multi_present": [3, 4], "comma": ["old"]},
            {"single": "9", "multi_missing": "3", "multi_present": "3", "comma": "0,100,1000"},
        )

        self.assertEqual(aligned["single"], [9])
        self.assertEqual(aligned["multi_missing"], [3, 1, 2])
        self.assertEqual(aligned["multi_present"], [3, 4])
        self.assertEqual(aligned["comma"], ["0,100,1000"])

    def test_experiment_form_builds_invoice_experiment(self) -> None:
        experiment = wizard.build_experiment_from_form(experiment_form_fields(kind="invoice", output_invoice=True))

        self.assertEqual(experiment["name"], "x")
        self.assertEqual(experiment["commands"][0], "boot")
        self.assertEqual(experiment["commands"][-1]["command"], "inv")
        self.assertEqual(experiment["outputs"], ["network", "invoice"])

    def test_create_page_uses_file_selectors_and_preview(self) -> None:
        state = wizard.WizardState(
            properties_path=wizard.DEFAULT_PROPERTIES,
            dse_json_path=wizard.DEFAULT_DSE_JSON,
            parameters={"seed": "1", "base_fee_set": "0,100,1000"},
            selected_parameters={"seed": [1, 7]},
            experiment={"name": "x", "commands": ["boot"], "outputs": ["network"]},
            value_text_overrides={},
        )

        html = wizard.render_create({}, state=state).decode("utf-8")

        self.assertIn('data-browse-action="/create/load"', html)
        self.assertIn('data-browse-action="/create/save"', html)
        self.assertIn('id="dse_json_path"', html)
        self.assertIn('name="dse_json_path"', html)
        self.assertIn('data-browse-target="dse_json_path" data-browse-mode="json"', html)
        self.assertIn('data-browse-target="dse_json_path" data-browse-mode="save_json"', html)
        self.assertIn(">Load</button>", html)
        self.assertIn(">Save</button>", html)
        self.assertIn('id="refresh-json-preview"', html)
        self.assertIn('id="deselect-all-parameters"', html)
        self.assertIn(">Deselect All</button>", html)
        self.assertIn('id="dse-json-preview"', html)
        self.assertIn("[hidden] { display: none !important; }", html)
        self.assertIn('<details class="panel panel-files">', html)
        self.assertIn('<details class="panel panel-experiment">', html)
        self.assertIn('<details class="panel panel-parameters">', html)
        self.assertIn('<details class="panel panel-preview">', html)
        self.assertIn('<section class="category experiment-card" data-experiment-row>', html)
        self.assertIn('<details class="experiment-details" open>', html)
        self.assertIn('<details class="command-block command-network">', html)
        self.assertIn('<summary class="command-title">', html)
        self.assertNotIn('<details class="panel" open>', html)
        self.assertNotIn('<details class="command-block" open>', html)
        self.assertIn("Experiment section of the DSE JSON", html)
        self.assertNotIn("Generated experiment", html)
        self.assertNotIn("experiment-summary", html)
        self.assertNotIn("Use experiment", html)
        self.assertNotIn("experiment_count", html)
        self.assertNotIn("exp_enabled_", html)
        self.assertIn("Recipe", html)
        self.assertIn("Network source", html)
        self.assertIn("Load .dat snapshot", html)
        self.assertIn('data-browse-mode="dat"', html)
        self.assertIn("Balance command", html)
        self.assertIn("Path finding command", html)
        self.assertIn("Invoice campaign command", html)
        self.assertIn("Changing recipe hides the current command", html)
        self.assertNotIn("Experiment definition", html)
        self.assertIn("&quot;0,100,1000&quot;", html)
        self.assertNotIn('name="save_path"', html)
        self.assertNotIn("Load DSE JSON", html)
        self.assertNotIn("Save DSE JSON", html)
        self.assertNotIn('formaction="/create/load"', html)
        self.assertNotIn(">Load JSON</button>", html)
        self.assertNotIn(">Save JSON</button>", html)
        self.assertNotIn("Value mode", html)

    def test_experiment_recipe_controls_visible_command_sections(self) -> None:
        invoice_row = wizard.default_experiment_row()
        invoice_row["enabled"] = "on"
        invoice_row["name"] = "invoice_test"
        invoice_row["kind"] = "invoice"

        invoice_html = wizard.render_experiment_editor(invoice_row)

        self.assertIn(">Bootstrap, balance, and invoice campaign</option>", invoice_html)
        self.assertIn('<details class="command-block command-path" data-command-block="path" hidden>', invoice_html)
        self.assertIn('<details class="command-block command-route" data-command-block="route" hidden>', invoice_html)
        self.assertIn('<details class="command-block command-invoice" data-command-block="invoice">', invoice_html)
        self.assertNotIn('data-command-block="invoice" hidden', invoice_html)

        bootstrap_row = wizard.default_experiment_row()
        bootstrap_row["kind"] = "bootstrap"
        bootstrap_html = wizard.render_experiment_editor(bootstrap_row)

        self.assertIn("data-balance-block hidden", bootstrap_html)
        self.assertIn('data-command-block="path" hidden', bootstrap_html)
        self.assertIn('data-command-block="route" hidden', bootstrap_html)
        self.assertIn('data-command-block="invoice" hidden', bootstrap_html)

    def test_experiment_form_builds_boot_snapshot_command(self) -> None:
        form = experiment_form_fields(kind="bootstrap")
        form["exp_boot_mode_0"] = ["load"]
        form["exp_boot_file_0"] = ["snapshots/network.dat"]

        experiment = wizard.build_experiment_from_form(form)

        self.assertEqual(
            experiment["commands"][0],
            {"command": "boot", "mode": "load", "file": "snapshots/network.dat"},
        )

    def test_experiment_form_requires_boot_snapshot_file(self) -> None:
        form = experiment_form_fields(kind="bootstrap")
        form["exp_boot_mode_0"] = ["load"]
        form["exp_boot_file_0"] = [""]

        with self.assertRaisesRegex(ValueError, "boot snapshot file is required"):
            wizard.build_experiment_from_form(form)

    def test_inactive_recipe_fields_are_preserved_but_not_validated(self) -> None:
        form = experiment_form_fields(kind="path", balance="none")
        form["exp_inv_blocks_0"] = ["not-an-integer"]

        experiment = wizard.build_experiment_from_form(form)

        self.assertEqual(experiment["commands"][-1]["command"], "path")
        self.assertNotIn("inv", [wizard.command_name(command) for command in experiment["commands"]])

    def test_experiment_row_loads_boot_snapshot_command(self) -> None:
        row = wizard.experiment_row_from_spec(
            {
                "name": "load_snapshot",
                "commands": [{"command": "boot", "mode": "load", "file": "snapshots/base.dat"}],
                "outputs": ["network"],
            }
        )

        self.assertEqual(row["boot_mode"], "load")
        self.assertEqual(row["boot_file"], "snapshots/base.dat")

    def test_load_dse_json_rejects_experiments_array(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "multi.json"
            path.write_text(
                json.dumps(
                    {
                        "parameters": {"seed": [1]},
                        "experiments": [
                            {"name": "first", "commands": ["boot"], "outputs": ["network"]},
                            {"name": "second", "commands": ["boot"], "outputs": ["network"]},
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "not an 'experiments' array"):
                wizard.load_dse_json(path)

    def test_load_dse_json_rejects_invalid_parameter_shape(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.json"
            path.write_text(
                json.dumps(
                    {
                        "parameters": {"seed": 1},
                        "experiment": {"name": "x", "commands": ["boot"], "outputs": ["network"]},
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
                        "experiment": {"name": "x", "commands": ["boot"], "outputs": ["network"]},
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
                        "experiment": {"name": "x", "commands": ["boot"], "outputs": ["network"]},
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


class DseRunnerTest(unittest.TestCase):
    def test_prepare_experiment_resolves_relative_boot_snapshot_path(self) -> None:
        runner = load_dse_run_module()
        with tempfile.TemporaryDirectory() as tmp:
            dse_dir = Path(tmp) / "dse"
            dse_dir.mkdir()

            experiment = {
                "name": "load_snapshot",
                "commands": [
                    {"command": "boot", "mode": "load", "file": "snapshots/base.dat"},
                    "rndbal",
                ],
                "outputs": ["network"],
            }
            prepared = runner.prepare_experiment_for_run(experiment, dse_dir)

        self.assertEqual(prepared["commands"][0]["mode"], "load")
        self.assertEqual(prepared["commands"][0]["file"], str((dse_dir / "snapshots" / "base.dat").resolve()))
        self.assertEqual(experiment["commands"][0]["file"], "snapshots/base.dat")

    def test_prepare_experiment_accepts_snapshot_aliases_without_mutating_source(self) -> None:
        runner = load_dse_run_module()
        with tempfile.TemporaryDirectory() as tmp:
            dse_dir = Path(tmp).resolve()
            experiment = {
                "name": "load_snapshot",
                "commands": [{"command": "boot", "source": "snapshot", "snapshot": "base.dat"}],
                "outputs": ["network"],
            }

            prepared = runner.prepare_experiment_for_run(experiment, dse_dir)

        self.assertEqual(prepared["commands"][0]["mode"], "load")
        self.assertEqual(prepared["commands"][0]["file"], str(dse_dir / "base.dat"))
        self.assertEqual(experiment["commands"][0]["source"], "snapshot")
        self.assertNotIn("file", experiment["commands"][0])


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
