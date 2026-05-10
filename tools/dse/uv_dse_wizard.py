#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
import math
import os
import secrets
import signal
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import webbrowser
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

sys.dont_write_bytecode = True

import uv_dse_common as dse_common
import uv_dse_visualizer as visualizer

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_PROPERTIES = "../../uv_configs/template.properties"
DEFAULT_RUN_DIR = "dse_runs/quickstart"
DEFAULT_DSE_JSON = "quickstart_dse.json"
MAX_JOB_OUTPUT_CHARS = 500_000
SESSION_TOKEN = secrets.token_urlsafe(24)


PARAMETER_CATEGORY_ORDER = [
    "General Settings",
    "Lightning Network",
    "Path Finding",
    "Simulation Time",
    "Bootstrap",
    "Node Profile: small",
    "Node Profile: medium",
    "Node Profile: hub",
    "Node Profile: default",
    "Fee Sets",
    "DSE JSON only",
    "Other",
]

CATEGORY_STYLE_CLASS = {
    "General Settings": "category-general",
    "Lightning Network": "category-network",
    "Path Finding": "category-path",
    "Simulation Time": "category-time",
    "Bootstrap": "category-bootstrap",
    "Fee Sets": "category-fees",
    "DSE JSON only": "category-json",
    "Other": "category-other",
}

DEFAULT_EXPERIMENT = {
    "name": "quick_invoice_hops",
    "commands": [
        "boot",
        "rndbal",
        {
            "command": "inv",
            "node_events_per_block": 0.10,
            "blocks": 4,
            "min_amt": 50000,
            "max_amt": 100000,
            "max_fees": 1000,
            "path_finder": "lnd",
        },
    ],
    "outputs": ["network", "invoice"],
}

EXPERIMENT_KIND_LABELS = {
    "bootstrap": "Bootstrap and network stats",
    "path": "Bootstrap, balance, and path finding",
    "route": "Bootstrap, balance, and single route",
    "invoice": "Bootstrap, balance, and invoice campaign",
}

BALANCE_MODE_LABELS = {
    "none": "Do not set balances",
    "rndbal": "Random local balances",
    "bal": "Fixed local balance level",
}

BOOT_MODE_LABELS = {
    "scratch": "Bootstrap from DSE config",
    "load": "Load .dat snapshot",
}

PATH_FINDER_OPTIONS = ["lnd", "mini_dijkstra", "shortest_hop", "bfs", "all"]


@dataclass(frozen=True)
class WizardState:
    properties_path: str
    dse_json_path: str
    parameters: dict[str, str]
    selected_parameters: dict[str, list[object]]
    experiment: dict[str, object]
    value_text_overrides: dict[str, str]
    experiment_row: dict[str, str] | None = None
    message: str = ""
    error: str = ""


@dataclass
class RunJob:
    job_id: str
    command: list[str]
    query: dict[str, str]
    status: str = "running"
    return_code: int | None = None
    output: str = ""
    error: str = ""
    started_at: float = 0.0
    finished_at: float | None = None
    process: subprocess.Popen[str] | None = None
    lock: threading.Lock | None = None


RUN_JOBS: dict[str, RunJob] = {}
RUN_JOBS_LOCK = threading.Lock()


def resolve_tool_path(value: str | None, *, default: str | None = None) -> Path:
    raw = (value or default or "").strip()
    if not raw:
        raise ValueError("Missing path")
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = SCRIPT_DIR / path
    return path.resolve()


def display_path(path: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(SCRIPT_DIR).as_posix()
    except ValueError:
        pass
    try:
        resolved.relative_to(REPO_ROOT)
        return Path(os.path.relpath(resolved, SCRIPT_DIR)).as_posix()
    except ValueError:
        return str(resolved)


def has_dse_index(path: Path) -> bool:
    return (path / "runs_index.jsonl").is_file() or (path / "runs_index.csv").is_file()


def missing_dse_output_message(input_dir: str) -> str:
    return (
        f"No runs_index.jsonl or runs_index.csv found in {input_dir}. "
        "Use Run DSE first, or select an output directory produced by uv_dse_run."
    )


def load_wizard_dataset(input_dir: str) -> visualizer.DseDataset:
    path = resolve_tool_path(input_dir)
    if not has_dse_index(path):
        raise ValueError(missing_dse_output_message(input_dir))
    return visualizer.load_dataset(path)


def nearest_existing_directory(path: Path) -> Path:
    candidate = path
    if candidate.exists() and candidate.is_file():
        candidate = candidate.parent
    while not candidate.exists() and candidate != candidate.parent:
        candidate = candidate.parent
    if candidate.is_dir():
        return candidate
    return SCRIPT_DIR


def native_select_path(query: dict[str, str]) -> dict[str, object]:
    mode = query.get("mode", "file")
    current_value = query.get("path", ".")
    try:
        current_path = resolve_tool_path(current_value, default=".")
    except Exception:
        current_path = SCRIPT_DIR
    directory = nearest_existing_directory(current_path)
    save_name = default_save_name(mode, current_path)

    try:
        if sys.platform == "darwin":
            selected = select_path_macos(mode, directory, save_name)
        elif sys.platform.startswith("linux"):
            selected = select_path_linux(mode, directory, save_name)
        else:
            raise RuntimeError("Native file selection is supported only on macOS and Ubuntu/Linux.")
    except RuntimeError as exc:
        return {"error": str(exc)}

    if selected is None:
        return {"cancelled": True}
    if mode == "save_json" and not selected.name.endswith(".json"):
        selected = selected.with_name(f"{selected.name}.json")
    return {"path": display_path(selected)}


def default_save_name(mode: str, path: Path) -> str:
    if mode == "save_json":
        name = path.name if path.name and path.suffix else Path(DEFAULT_DSE_JSON).name
        return name if name.endswith(".json") else f"{name}.json"
    return path.name


def dialog_title(mode: str) -> str:
    return {
        "properties": "Select UltraViolet properties file",
        "json": "Select DSE JSON file",
        "save_json": "Save DSE JSON file",
        "dir": "Select DSE output directory",
        "dat": "Select UltraViolet .dat snapshot",
    }.get(mode, "Select file")


def select_path_macos(mode: str, directory: Path, save_name: str) -> Path | None:
    if shutil.which("osascript") is None:
        raise RuntimeError("macOS native file selector requires osascript.")

    default_location = applescript_string(str(directory))
    prompt = applescript_string(dialog_title(mode))
    if mode == "dir":
        script = (
            f"set chosenItem to choose folder with prompt {prompt} "
            f"default location (POSIX file {default_location})\n"
            "POSIX path of chosenItem\n"
        )
    elif mode == "save_json":
        script = (
            f"set chosenItem to choose file name with prompt {prompt} "
            f"default name {applescript_string(save_name)} "
            f"default location (POSIX file {default_location})\n"
            "POSIX path of chosenItem\n"
        )
    else:
        script = (
            f"set chosenItem to choose file with prompt {prompt} "
            f"default location (POSIX file {default_location})\n"
            "POSIX path of chosenItem\n"
        )

    return run_dialog_command(["osascript", "-"], input_text=script)


def applescript_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def select_path_linux(mode: str, directory: Path, save_name: str) -> Path | None:
    if shutil.which("zenity"):
        return select_path_zenity(mode, directory, save_name)
    if shutil.which("kdialog"):
        return select_path_kdialog(mode, directory, save_name)
    raise RuntimeError(
        "No native Linux file selector was found. On Ubuntu, install zenity "
        "with: sudo apt install zenity"
    )


def select_path_zenity(mode: str, directory: Path, save_name: str) -> Path | None:
    command = ["zenity", "--file-selection", f"--title={dialog_title(mode)}"]
    if mode == "dir":
        command.append("--directory")
        command.append(f"--filename={directory}/")
    elif mode == "save_json":
        command.extend(["--save", "--confirm-overwrite", f"--filename={directory / save_name}"])
        command.append("--file-filter=JSON files | *.json")
    else:
        command.append(f"--filename={directory}/")
        if mode == "properties":
            command.append("--file-filter=Properties files | *.properties")
        elif mode == "json":
            command.append("--file-filter=JSON files | *.json")
        elif mode == "dat":
            command.append("--file-filter=UltraViolet snapshots | *.dat")
        command.append("--file-filter=All files | *")
    return run_dialog_command(command)


def select_path_kdialog(mode: str, directory: Path, save_name: str) -> Path | None:
    start = str(directory)
    if mode == "dir":
        command = ["kdialog", "--getexistingdirectory", start]
    elif mode == "save_json":
        command = ["kdialog", "--getsavefilename", str(directory / save_name), "*.json"]
    elif mode == "properties":
        command = ["kdialog", "--getopenfilename", start, "*.properties"]
    elif mode == "json":
        command = ["kdialog", "--getopenfilename", start, "*.json"]
    elif mode == "dat":
        command = ["kdialog", "--getopenfilename", start, "*.dat"]
    else:
        command = ["kdialog", "--getopenfilename", start]
    return run_dialog_command(command)


def run_dialog_command(command: list[str], input_text: str | None = None) -> Path | None:
    completed = subprocess.run(
        command,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=SCRIPT_DIR,
    )
    if completed.returncode != 0:
        message = (completed.stderr or completed.stdout or "").strip()
        if completed.returncode in {1, -128} or "User canceled" in message or "cancel" in message.lower():
            return None
        raise RuntimeError(message or f"File selector failed with exit code {completed.returncode}.")
    selected = completed.stdout.strip()
    if not selected:
        return None
    return Path(selected).expanduser().resolve()


def native_selector_diagnostic() -> str:
    if sys.platform == "darwin":
        return "" if shutil.which("osascript") else "Native macOS file selection requires osascript."
    if sys.platform.startswith("linux"):
        if shutil.which("zenity") or shutil.which("kdialog"):
            return ""
        return "Native file selection on Ubuntu/Linux requires zenity or kdialog. Install zenity with: sudo apt install zenity"
    return "Native file selection is supported only on macOS and Ubuntu/Linux."


def native_selector_notice_html() -> str:
    diagnostic = native_selector_diagnostic()
    if not diagnostic:
        return ""
    return f'<div class="notice">{escape(diagnostic)}</div>'


def load_properties_with_includes(path: Path) -> dict[str, str]:
    return dse_common.load_properties_with_includes(path)

def parse_json_values(text: str) -> list[object]:
    cleaned = text.strip()
    if not cleaned:
        return []
    try:
        parsed = json.loads(f"[{cleaned}]")
        if isinstance(parsed, list):
            return parsed
    except json.JSONDecodeError as exc:
        if '"' in cleaned:
            raise ValueError(
                "Invalid DSE value list. Use commas between alternatives and JSON double quotes "
                "around any single value that contains commas."
            ) from exc

    values: list[object] = []
    for raw in text.split(","):
        token = raw.strip()
        if not token:
            continue
        try:
            values.append(json.loads(token))
        except json.JSONDecodeError:
            values.append(token)
    return values


def stringify_values(values: list[object], *, quote_strings: bool = False) -> str:
    return ", ".join(stringify_value(value, quote_string=quote_strings) for value in values)


def stringify_value(value: object, *, quote_string: bool = False) -> str:
    if isinstance(value, str):
        if quote_string or "," in value or value.strip() != value or value == "":
            return json.dumps(value, separators=(",", ":"))
        return value
    return json.dumps(value, separators=(",", ":"))


def default_value_text(value: str) -> str:
    return stringify_value(value)


def template_property_dse_value(value: str) -> object:
    text = value.strip()
    if value != text or "," in value or text == "":
        return value
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return value
    try:
        if dse_common.stringify_property_value("template", parsed) == text.lower():
            return parsed
    except ValueError:
        pass
    return value


def parameter_value_matches(left: object, right: object) -> bool:
    try:
        return dse_common.stringify_property_value("template", left) == dse_common.stringify_property_value(
            "template", right
        )
    except ValueError:
        return left == right


def align_default_parameter_space_with_properties(
    selected: dict[str, list[object]],
    properties: dict[str, str],
) -> dict[str, list[object]]:
    aligned: dict[str, list[object]] = {}
    for name, values in selected.items():
        if name not in properties:
            aligned[name] = list(values)
            continue
        template_value = template_property_dse_value(properties[name])
        if len(values) == 1:
            aligned[name] = [template_value]
            continue
        if any(parameter_value_matches(template_value, value) for value in values):
            aligned[name] = list(values)
        else:
            aligned[name] = [template_value, *values]
    return aligned


def should_align_startup_defaults(
    params: dict[str, str],
    properties_path: str,
    dse_json_path: str,
) -> bool:
    if params.get("load_json"):
        return False
    try:
        return (
            resolve_tool_path(properties_path) == resolve_tool_path(DEFAULT_PROPERTIES)
            and resolve_tool_path(dse_json_path) == resolve_tool_path(DEFAULT_DSE_JSON)
        )
    except ValueError:
        return False


def load_dse_json(path: Path) -> tuple[dict[str, list[object]], dict[str, object]]:
    if not path.is_file():
        raise ValueError(f"DSE JSON file not found: {path}")
    payload = dse_common.validate_dse_payload(dse_common.load_json_object(path))
    return payload["parameters"], payload["experiment"]


def command_name(command: object) -> str:
    if isinstance(command, str):
        return command.strip().lower()
    if isinstance(command, dict):
        return str(command.get("command", command.get("cmd", ""))).strip().lower()
    return ""


def command_value(command: object, key: str, default: object = "") -> object:
    if isinstance(command, dict):
        return command.get(key, default)
    return default


def command_for_name(commands: list[object], name: str) -> object | None:
    for command in commands:
        if command_name(command) == name:
            return command
    return None


def string_field(value: object, default: str = "") -> str:
    if value is None:
        return default
    return str(value)


def experiment_row_from_spec(experiment: dict[str, object]) -> dict[str, str]:
    commands = experiment.get("commands", [])
    if not isinstance(commands, list):
        commands = []

    row = default_experiment_row()
    row["name"] = string_field(experiment.get("name"), "experiment")

    outputs = experiment.get("outputs", experiment.get("reports", ["network"]))
    if isinstance(outputs, str):
        outputs = [outputs]
    row["output_network"] = "on" if "network" in outputs or "stat" in outputs or "stats" in outputs else ""
    row["output_invoice"] = "on" if "invoice" in outputs or "invoices" in outputs or "invoice_report" in outputs else ""

    boot = command_for_name(commands, "boot")
    boot_mode = string_field(command_value(boot, "mode", command_value(boot, "source", "scratch"))).strip().lower()
    if boot_mode in {"load", "snapshot", "dat"}:
        row["boot_mode"] = "load"
        row["boot_file"] = string_field(
            command_value(boot, "file", command_value(boot, "path", command_value(boot, "snapshot", "")))
        )
    else:
        row["boot_mode"] = "scratch"

    rndbal = command_for_name(commands, "rndbal")
    bal = command_for_name(commands, "bal")
    if bal is not None:
        row["balance"] = "bal"
        row["bal_level"] = string_field(command_value(bal, "level", row["bal_level"]))
        row["min_delta"] = string_field(command_value(bal, "min_delta", row["min_delta"]))
    elif rndbal is not None:
        row["balance"] = "rndbal"
        row["min_delta"] = string_field(command_value(rndbal, "min_delta", row["min_delta"]))

    path = command_for_name(commands, "path")
    route = command_for_name(commands, "route")
    inv = command_for_name(commands, "inv")
    if path is not None:
        row["kind"] = "path"
        row["path_start"] = string_field(command_value(path, "start", command_value(path, "sender", row["path_start"])))
        row["path_destination"] = string_field(
            command_value(path, "destination", command_value(path, "dest", row["path_destination"]))
        )
        row["path_amount"] = string_field(command_value(path, "amount", row["path_amount"]))
        row["path_path_finder"] = string_field(command_value(path, "path_finder", row["path_path_finder"]))
        row["path_topk"] = string_field(command_value(path, "topk", row["path_topk"]))
    elif route is not None:
        row["kind"] = "route"
        row["route_sender"] = string_field(command_value(route, "sender", command_value(route, "start", row["route_sender"])))
        row["route_destination"] = string_field(
            command_value(route, "destination", command_value(route, "dest", row["route_destination"]))
        )
        row["route_amount"] = string_field(command_value(route, "amount", row["route_amount"]))
        row["route_max_fees"] = string_field(command_value(route, "max_fees", row["route_max_fees"]))
        row["route_path_finder"] = string_field(command_value(route, "path_finder", row["route_path_finder"]))
        row["route_message"] = string_field(command_value(route, "message", row["route_message"]))
    elif inv is not None:
        row["kind"] = "invoice"
        row["inv_node_events_per_block"] = string_field(
            command_value(inv, "node_events_per_block", row["inv_node_events_per_block"])
        )
        row["inv_blocks"] = string_field(command_value(inv, "blocks", command_value(inv, "duration_blocks", row["inv_blocks"])))
        row["inv_min_amt"] = string_field(command_value(inv, "min_amt", command_value(inv, "amount_min", row["inv_min_amt"])))
        row["inv_max_amt"] = string_field(command_value(inv, "max_amt", command_value(inv, "amount_max", row["inv_max_amt"])))
        row["inv_max_fees"] = string_field(command_value(inv, "max_fees", row["inv_max_fees"]))
        row["inv_path_finder"] = string_field(command_value(inv, "path_finder", row["inv_path_finder"]))
    else:
        row["kind"] = "bootstrap"

    return row


def default_experiment_row() -> dict[str, str]:
    return {
        "name": "experiment",
        "kind": "invoice",
        "boot_mode": "scratch",
        "boot_file": "",
        "balance": "rndbal",
        "output_network": "on",
        "output_invoice": "on",
        "bal_level": "0.5",
        "min_delta": "10000",
        "path_start": "pk0",
        "path_destination": "pk7",
        "path_amount": "10000",
        "path_path_finder": "lnd",
        "path_topk": "5",
        "route_sender": "pk0",
        "route_destination": "pk7",
        "route_amount": "10000",
        "route_path_finder": "lnd",
        "route_max_fees": "1000",
        "route_message": "",
        "inv_node_events_per_block": "0.08",
        "inv_blocks": "4",
        "inv_min_amt": "50000",
        "inv_max_amt": "100000",
        "inv_max_fees": "1000",
        "inv_path_finder": "lnd",
    }


def experiment_row_for_render(experiment: dict[str, object]) -> dict[str, str]:
    return experiment_row_from_spec(experiment)


def experiment_row_from_form(form: dict[str, list[str]]) -> dict[str, str]:
    row = default_experiment_row()
    for key in row:
        row[key] = form_value(form, f"exp_{key}_0", row[key])
    row["output_network"] = "on" if form_value(form, "exp_output_network_0", "") == "on" else ""
    row["output_invoice"] = "on" if form_value(form, "exp_output_invoice_0", "") == "on" else ""
    return row


def build_experiment_from_form(form: dict[str, list[str]]) -> dict[str, object]:
    return build_experiment_from_row(experiment_row_from_form(form), 1)


def build_experiment_from_form_row(row: dict[str, str]) -> dict[str, object]:
    return build_experiment_from_row(row, 1)


def build_experiment_from_row(row: dict[str, str], index: int) -> dict[str, object]:
    name = row.get("name", "").strip() or f"experiment_{index}"
    kind = row.get("kind", "invoice")
    if kind not in EXPERIMENT_KIND_LABELS:
        raise ValueError(f"Experiment '{name}' has unsupported type '{kind}'.")

    commands: list[object] = [build_boot_command(row, name)]
    balance = row.get("balance", "none")
    if kind == "bootstrap":
        balance = "none"
    min_delta = optional_int(row, "min_delta", f"Experiment '{name}' min_delta")
    if balance == "rndbal":
        commands.append({"command": "rndbal", "min_delta": min_delta} if min_delta is not None else "rndbal")
    elif balance == "bal":
        level = required_float(row, "bal_level", f"Experiment '{name}' balance level")
        command: dict[str, object] = {"command": "bal", "level": level}
        if min_delta is not None:
            command["min_delta"] = min_delta
        commands.append(command)
    elif balance != "none":
        raise ValueError(f"Experiment '{name}' has unsupported balance mode '{balance}'.")

    path_finder_by_kind = {
        "path": row.get("path_path_finder", row.get("path_finder", "lnd")).strip() or "lnd",
        "route": row.get("route_path_finder", row.get("path_finder", "lnd")).strip() or "lnd",
        "invoice": row.get("inv_path_finder", row.get("path_finder", "lnd")).strip() or "lnd",
        "bootstrap": "lnd",
    }
    path_finder = path_finder_by_kind.get(kind, "lnd")
    if path_finder == "all" and kind != "path":
        raise ValueError(f"Experiment '{name}' can use path_finder=all only for path experiments.")

    if kind == "path":
        commands.append(
            {
                "command": "path",
                "start": row.get("path_start", row.get("start", "pk0")).strip() or "pk0",
                "destination": row.get("path_destination", row.get("destination", "pk7")).strip() or "pk7",
                "amount": required_int(row, "path_amount", f"Experiment '{name}' path amount"),
                "path_finder": path_finder,
                "topk": required_int(row, "path_topk", f"Experiment '{name}' topk"),
            }
        )
    elif kind == "route":
        route_command: dict[str, object] = {
            "command": "route",
            "sender": row.get("route_sender", row.get("start", "pk0")).strip() or "pk0",
            "destination": row.get("route_destination", row.get("destination", "pk7")).strip() or "pk7",
            "amount": required_int(row, "route_amount", f"Experiment '{name}' route amount"),
            "max_fees": required_int(row, "route_max_fees", f"Experiment '{name}' route max_fees"),
            "path_finder": path_finder,
        }
        message = row.get("route_message", row.get("message", "")).strip()
        if message:
            route_command["message"] = message
        commands.append(route_command)
    elif kind == "invoice":
        commands.append(
            {
                "command": "inv",
                "node_events_per_block": required_float(
                    row, "inv_node_events_per_block", f"Experiment '{name}' node_events_per_block"
                ),
                "blocks": required_int(row, "inv_blocks", f"Experiment '{name}' blocks"),
                "min_amt": required_int(row, "inv_min_amt", f"Experiment '{name}' min_amt"),
                "max_amt": required_int(row, "inv_max_amt", f"Experiment '{name}' max_amt"),
                "max_fees": required_int(row, "inv_max_fees", f"Experiment '{name}' max_fees"),
                "path_finder": path_finder,
            }
        )

    outputs = []
    if row.get("output_network") == "on":
        outputs.append("network")
    if row.get("output_invoice") == "on":
        outputs.append("invoice")
    if not outputs:
        outputs.append("network")
    return {"name": name, "commands": commands, "outputs": outputs}


def build_boot_command(row: dict[str, str], experiment_name: str) -> object:
    mode = row.get("boot_mode", "scratch").strip().lower() or "scratch"
    if mode == "scratch":
        return "boot"
    if mode == "load":
        snapshot = row.get("boot_file", "").strip()
        if not snapshot:
            raise ValueError(f"Experiment '{experiment_name}' boot snapshot file is required.")
        return {"command": "boot", "mode": "load", "file": snapshot}
    raise ValueError(f"Experiment '{experiment_name}' has unsupported boot mode '{mode}'.")


def required_int(row: dict[str, str], key: str, label: str) -> int:
    value = row.get(key, "").strip()
    if not value:
        raise ValueError(f"{label} is required.")
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{label} must be an integer.") from exc


def optional_int(row: dict[str, str], key: str, label: str) -> int | None:
    value = row.get(key, "").strip()
    if not value:
        return None
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{label} must be an integer.") from exc


def required_float(row: dict[str, str], key: str, label: str) -> float:
    value = row.get(key, "").strip()
    if not value:
        raise ValueError(f"{label} is required.")
    try:
        return float(value)
    except ValueError as exc:
        raise ValueError(f"{label} must be numeric.") from exc


def default_create_state(params: dict[str, str], message: str = "", error: str = "") -> WizardState:
    properties_path = params.get("properties_path", DEFAULT_PROPERTIES)
    dse_json_path = params.get("dse_json_path", DEFAULT_DSE_JSON)

    selected: dict[str, list[object]] = {}
    experiment = dict(DEFAULT_EXPERIMENT)
    try:
        if dse_json_path:
            selected, experiment = load_dse_json(resolve_tool_path(dse_json_path))
            if params.get("load_json") or dse_json_path == DEFAULT_DSE_JSON:
                message = message or f"Loaded {dse_json_path}"
    except Exception as exc:
        error = str(exc)

    try:
        properties = load_properties_with_includes(resolve_tool_path(properties_path))
    except Exception as exc:
        properties = {}
        error = str(exc)

    if selected and properties and should_align_startup_defaults(params, properties_path, dse_json_path):
        selected = align_default_parameter_space_with_properties(selected, properties)

    return WizardState(
        properties_path=properties_path,
        dse_json_path=dse_json_path,
        parameters=properties,
        selected_parameters=selected,
        experiment=experiment,
        value_text_overrides={},
        message=message,
        error=error,
    )


def create_state_from_form(form: dict[str, list[str]], error: str = "", message: str = "") -> WizardState:
    properties_path = form_value(form, "properties_path", DEFAULT_PROPERTIES)
    dse_json_path = form_value(form, "dse_json_path", DEFAULT_DSE_JSON)
    try:
        properties = load_properties_with_includes(resolve_tool_path(properties_path))
    except Exception as exc:
        properties = {}
        error = f"{error}\n{exc}".strip()

    count = safe_form_count(form)
    selected: dict[str, list[object]] = {}
    value_text_overrides: dict[str, str] = {}
    for index in range(count):
        name = form_value(form, f"name_{index}", "")
        if not name:
            continue
        value_text = form_value(form, f"values_{index}", "")
        value_text_overrides[name] = value_text
        if form_value(form, f"include_{index}", "") == "on":
            try:
                selected[name] = parse_json_values(value_text)
            except ValueError:
                selected[name] = []

    experiment_row = experiment_row_from_form(form)
    try:
        experiment = build_experiment_from_form_row(experiment_row)
    except ValueError:
        experiment = {}

    return WizardState(
        properties_path=properties_path,
        dse_json_path=dse_json_path,
        parameters=properties,
        selected_parameters=selected,
        experiment=experiment,
        value_text_overrides=value_text_overrides,
        experiment_row=experiment_row,
        message=message,
        error=error,
    )


def parameter_sort_key(name: str) -> tuple[int, str]:
    category = parameter_category(name)
    category_index = PARAMETER_CATEGORY_ORDER.index(category) if category in PARAMETER_CATEGORY_ORDER else len(PARAMETER_CATEGORY_ORDER)
    priority = [
        "bootstrap_nodes",
        "bootstrap_blocks",
        "seed",
        "pathfinding_max_hops",
        "blocktime_ms",
        "node_services_tick_ms",
        "gossip_flush_period_ms",
        "p2p_max_age",
        "gossip_flush_size",
    ]
    if name in priority:
        return (category_index, f"{priority.index(name):03d}")
    if name.startswith("pathfinding_"):
        return (category_index, name)
    if name.startswith("profile."):
        return (category_index, name)
    return (category_index, name)


def parameter_category(name: str) -> str:
    if name in {"debug", "logfile", "seed", "max_threads"}:
        return "General Settings"
    if name in {"to_self_delay", "minimum_depth", "p2p_max_hops", "p2p_max_age", "gossip_flush_size"}:
        return "Lightning Network"
    if name.startswith("pathfinding_"):
        return "Path Finding"
    if name in {"blocktime_ms", "node_services_tick_ms", "gossip_flush_period_ms"}:
        return "Simulation Time"
    if name.startswith("bootstrap_"):
        return "Bootstrap"
    if name == "base_fee_set":
        return "Fee Sets"
    if name.startswith("profile."):
        parts = name.split(".")
        if len(parts) >= 3:
            return f"Node Profile: {parts[1]}"
        return "Other"
    return "Other"


def category_style_class(category: str) -> str:
    if category.startswith("Node Profile:"):
        return "category-profile"
    return CATEGORY_STYLE_CLASS.get(category, "category-other")


def selected_space_size(selected_parameters: dict[str, list[object]]) -> tuple[int, int]:
    non_empty = [values for values in selected_parameters.values() if values]
    if not non_empty:
        return 0, 0
    return len(non_empty), math.prod(len(values) for values in non_empty)


def save_dse_from_form(form: dict[str, list[str]]) -> tuple[Path, dict[str, object]]:
    count = strict_form_count(form)
    parameters: dict[str, list[object]] = {}
    for index in range(count):
        name = form_value(form, f"name_{index}", "")
        if not name or form_value(form, f"include_{index}", "") != "on":
            continue
        try:
            values = parse_json_values(form_value(form, f"values_{index}", ""))
        except ValueError as exc:
            raise ValueError(f"Parameter '{name}' has invalid DSE values: {exc}") from exc
        if not values:
            raise ValueError(f"Parameter '{name}' has no values")
        parameters[name] = values

    if not parameters:
        raise ValueError("Select at least one parameter")
    dse_common.validate_parameters(parameters)

    experiment = build_experiment_from_form(form)
    payload = dse_common.validate_dse_payload({"parameters": parameters, "experiment": experiment})
    output_path = resolve_tool_path(form_value(form, "dse_json_path", DEFAULT_DSE_JSON))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return output_path, payload


def form_value(form: dict[str, list[str]], key: str, default: str = "") -> str:
    values = form.get(key)
    return values[-1] if values else default


def safe_form_count(form: dict[str, list[str]]) -> int:
    try:
        return max(0, int(form_value(form, "parameter_count", "0")))
    except ValueError:
        return 0


def strict_form_count(form: dict[str, list[str]]) -> int:
    try:
        count = int(form_value(form, "parameter_count", "0"))
    except ValueError as exc:
        raise ValueError("Invalid parameter count in submitted form.") from exc
    if count < 0:
        raise ValueError("Invalid parameter count in submitted form.")
    return count


def build_run_query(form: dict[str, list[str]]) -> dict[str, str]:
    return {
        "base_properties": form_value(form, "base_properties", DEFAULT_PROPERTIES),
        "dse_json": form_value(form, "dse_json", DEFAULT_DSE_JSON),
        "output_dir": form_value(form, "output_dir", DEFAULT_RUN_DIR),
        "limit": form_value(form, "limit", ""),
        "force": "1" if form_value(form, "force", "") == "on" else "",
        "force_confirm": "1" if form_value(form, "force_confirm", "") == "on" else "",
    }


def build_dse_command(form: dict[str, list[str]]) -> tuple[list[str], dict[str, str]]:
    force = form_value(form, "force", "") == "on"
    if force and form_value(form, "force_confirm", "") != "on":
        raise ValueError("Confirm replacement before running with --force.")

    base_properties = resolve_tool_path(form_value(form, "base_properties", DEFAULT_PROPERTIES))
    dse_json = resolve_tool_path(form_value(form, "dse_json", DEFAULT_DSE_JSON))
    output_dir = resolve_tool_path(form_value(form, "output_dir", DEFAULT_RUN_DIR))
    if not base_properties.is_file():
        raise ValueError(f"Base properties file not found: {display_path(base_properties)}")
    if not dse_json.is_file():
        raise ValueError(f"DSE JSON file not found: {display_path(dse_json)}")
    if output_dir.exists() and not output_dir.is_dir():
        raise ValueError(f"Output path exists but is not a directory: {display_path(output_dir)}")
    if output_dir.exists() and not force:
        raise ValueError("Output directory already exists. Choose a new directory or explicitly enable replacement.")

    limit = form_value(form, "limit", "").strip()
    if limit:
        try:
            if int(limit) <= 0:
                raise ValueError
        except ValueError as exc:
            raise ValueError("Limit must be a positive integer.") from exc

    command = [
        sys.executable,
        str(SCRIPT_DIR / "uv_dse_run"),
        str(base_properties),
        str(dse_json),
        "--output-dir",
        str(output_dir),
    ]
    if force:
        command.append("--force")
    if limit:
        command.extend(["--limit", limit])
    return command, build_run_query(form)


def start_run_job(form: dict[str, list[str]]) -> RunJob:
    command, query = build_dse_command(form)
    job = RunJob(
        job_id=uuid.uuid4().hex,
        command=command,
        query=query,
        started_at=time.time(),
        lock=threading.Lock(),
    )
    with RUN_JOBS_LOCK:
        RUN_JOBS[job.job_id] = job
    thread = threading.Thread(target=run_job_worker, args=(job,), daemon=True)
    thread.start()
    return job


def run_job_worker(job: RunJob) -> None:
    try:
        process = subprocess.Popen(
            job.command,
            cwd=SCRIPT_DIR,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=1,
            start_new_session=True,
        )
        with job.lock or threading.Lock():
            job.process = process
        assert process.stdout is not None
        try:
            for line in process.stdout:
                append_job_output(job, line)
        finally:
            process.stdout.close()
        return_code = process.wait()
        with job.lock or threading.Lock():
            if job.status != "cancelled":
                job.status = "completed" if return_code == 0 else "failed"
            job.return_code = return_code
            job.finished_at = time.time()
    except Exception as exc:
        with job.lock or threading.Lock():
            job.status = "failed"
            job.error = str(exc)
            job.finished_at = time.time()
        append_job_output(job, f"\n{exc}\n")


def append_job_output(job: RunJob, text: str) -> None:
    with job.lock or threading.Lock():
        job.output = (job.output + text)[-MAX_JOB_OUTPUT_CHARS:]


def cancel_run_job(job_id: str) -> bool:
    job = get_run_job(job_id)
    if not job:
        return False
    with job.lock or threading.Lock():
        job.status = "cancelled"
        process = job.process
    if process and process.poll() is None:
        terminate_process_group(process)
    return True


def terminate_process_group(process: subprocess.Popen[str]) -> None:
    try:
        if hasattr(os, "killpg"):
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
    except ProcessLookupError:
        return


def get_run_job(job_id: str) -> RunJob | None:
    with RUN_JOBS_LOCK:
        return RUN_JOBS.get(job_id)


def run_job_payload(job_id: str) -> dict[str, object]:
    job = get_run_job(job_id)
    if not job:
        return {"error": "Run job not found."}
    with job.lock or threading.Lock():
        return {
            "job_id": job.job_id,
            "status": job.status,
            "return_code": job.return_code,
            "output": job.output,
            "error": job.error,
            "done": job.status in {"completed", "failed", "cancelled"},
            "elapsed_seconds": round((job.finished_at or time.time()) - job.started_at, 1),
        }


def page(title: str, body: str, message: str = "", error: str = "") -> bytes:
    message_html = f'<div class="notice">{escape(message)}</div>' if message else ""
    error_html = f'<div class="error">{escape(error)}</div>' if error else ""
    selector_notice = native_selector_notice_html()
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{escape(title)}</title>
  <style>
    :root {{
      --semantic-files: #2f6fae;
      --semantic-files-bg: #e8f1fb;
      --semantic-experiment: #a26000;
      --semantic-experiment-bg: #fff3d7;
      --semantic-parameters: #2c7a5a;
      --semantic-parameters-bg: #e9f6f1;
      --semantic-preview: #4f5d75;
      --semantic-preview-bg: #eef2f6;
      --semantic-run: #1f766b;
      --semantic-run-bg: #e4f3f1;
      --semantic-visualize: #6d5a8f;
      --semantic-visualize-bg: #f0edf8;
      --semantic-neutral: #6b7280;
      --semantic-neutral-bg: #f2f5f8;
    }}
    body {{ margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; font-size: 14px; color: #151719; background: #f3f5f7; }}
    header {{ background: #fbfcfd; border-bottom: 1px solid #d8dde3; padding: 14px 18px; display: flex; align-items: center; gap: 16px; }}
    header a {{ color: var(--semantic-files); text-decoration: none; font-size: 13px; font-weight: 800; }}
    h1 {{ margin: 0; font-size: 22px; line-height: 1.2; font-weight: 800; }}
    h2 {{ margin: 0 0 10px; font-size: 17px; line-height: 1.25; font-weight: 800; }}
    main {{ padding: 16px 18px 28px; }}
    .grid {{ display: grid; grid-template-columns: repeat(3, minmax(220px, 1fr)); gap: 14px; }}
    .card, .panel {{ background: #ffffff; border: 1px solid #d8dde3; padding: 14px; }}
    .card {{ border-top: 4px solid var(--section-accent, var(--semantic-neutral)); }}
    .panel {{ border-left: 4px solid var(--section-accent, var(--semantic-neutral)); }}
    .card-create, .panel-files {{ --section-accent: var(--semantic-files); --section-bg: var(--semantic-files-bg); --section-title: #173e66; --action-bg: var(--semantic-files); --action-border: #255c91; }}
    .card-run, .panel-run {{ --section-accent: var(--semantic-run); --section-bg: var(--semantic-run-bg); --section-title: #0f5149; --action-bg: var(--semantic-run); --action-border: #185f56; }}
    .card-visualize, .panel-visualize {{ --section-accent: var(--semantic-visualize); --section-bg: var(--semantic-visualize-bg); --section-title: #453765; --action-bg: var(--semantic-visualize); --action-border: #574873; }}
    .panel-experiment {{ --section-accent: var(--semantic-experiment); --section-bg: var(--semantic-experiment-bg); --section-title: #6e4000; --action-bg: var(--semantic-experiment); --action-border: #804c00; }}
    .panel-parameters {{ --section-accent: var(--semantic-parameters); --section-bg: var(--semantic-parameters-bg); --section-title: #18583f; --action-bg: var(--semantic-parameters); --action-border: #23664b; }}
    .panel-preview {{ --section-accent: var(--semantic-preview); --section-bg: var(--semantic-preview-bg); --section-title: #2f3c4f; --action-bg: var(--semantic-preview); --action-border: #3f4b60; }}
    [hidden] {{ display: none !important; }}
    details.panel, details.category, details.command-block, details.experiment-details {{ display: block; }}
    details > summary {{ cursor: pointer; }}
    details.panel > summary {{ list-style-position: inside; background: var(--section-bg, #e9eef4); color: var(--section-title, #20252c); border-bottom: 1px solid #d8dde3; margin: -14px -14px 0; padding: 10px 14px; }}
    details.panel:not([open]) > summary {{ border-bottom: 0; margin-bottom: -14px; }}
    details.panel > summary h2 {{ display: inline; margin: 0; font-size: 17px; font-weight: 800; }}
    .disclosure-body {{ margin-top: 12px; }}
    .card a, button, .button {{ display: inline-block; border: 1px solid var(--action-border, #174f91); background: var(--action-bg, #1a5fa8); color: #ffffff; padding: 8px 11px; font-weight: 700; text-decoration: none; cursor: pointer; }}
    form {{ display: grid; gap: 12px; }}
    label {{ display: grid; gap: 4px; font-size: 12px; font-weight: 800; color: #33383d; }}
    input, select, textarea {{ font: inherit; font-size: 13px; font-weight: 400; padding: 7px; border: 1px solid #b9c0c7; background: #ffffff; }}
    textarea {{ min-height: 180px; font-family: Menlo, Consolas, monospace; }}
    table {{ width: 100%; border-collapse: collapse; background: #ffffff; }}
    th, td {{ border-bottom: 1px solid #e2e6ea; padding: 6px; text-align: left; vertical-align: top; }}
    th {{ background: #f8f9fa; font-size: 12px; font-weight: 800; color: #30363d; }}
    td {{ font-size: 13px; }}
    .row {{ display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr)); gap: 10px; align-items: end; }}
    .row3 {{ display: grid; grid-template-columns: 2fr 2fr 1fr; gap: 10px; align-items: end; }}
    .actions {{ display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }}
    .secondary {{ border-color: #9ba6b1; background: #ffffff; color: #26323c; }}
    .path-field {{ display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; }}
    .path-field.multi-action {{ grid-template-columns: minmax(0, 1fr) auto auto; }}
    .space-summary {{ display: flex; flex-wrap: wrap; gap: 14px; align-items: baseline; margin: 0 0 12px; }}
    .space-summary strong {{ font-size: 20px; font-weight: 800; color: var(--semantic-parameters); }}
    .category {{ --category-accent: var(--semantic-neutral); --category-bg: var(--semantic-neutral-bg); --category-title: #424954; border: 1px solid #d8dde3; border-left: 3px solid var(--category-accent); margin-top: 10px; background: #ffffff; }}
    .category-general {{ --category-accent: #607085; --category-bg: #eef2f6; --category-title: #3b4654; }}
    .category-network {{ --category-accent: #2f6fae; --category-bg: #e8f1fb; --category-title: #173e66; }}
    .category-path {{ --category-accent: #5b5fa8; --category-bg: #eef0fb; --category-title: #393d75; }}
    .category-time {{ --category-accent: #287f89; --category-bg: #e6f4f6; --category-title: #16565d; }}
    .category-bootstrap {{ --category-accent: #a26000; --category-bg: #fff3d7; --category-title: #6e4000; }}
    .category-profile {{ --category-accent: #2c7a5a; --category-bg: #e9f6f1; --category-title: #18583f; }}
    .category-fees {{ --category-accent: #8a641f; --category-bg: #fbf0dc; --category-title: #5f4313; }}
    .category-json {{ --category-accent: #4f5d75; --category-bg: #eef2f6; --category-title: #2f3c4f; }}
    .category-other {{ --category-accent: #6b7280; --category-bg: #f2f5f8; --category-title: #424954; }}
    .category-header {{ display: flex; justify-content: space-between; gap: 12px; padding: 8px 10px; background: var(--category-bg); border-bottom: 1px solid #d8dde3; cursor: pointer; }}
    details.category:not([open]) > .category-header {{ border-bottom: 0; }}
    .category-header h3 {{ margin: 0; font-size: 15px; line-height: 1.25; font-weight: 800; color: var(--category-title); }}
    .category-header .muted {{ font-size: 12px; font-weight: 700; color: var(--category-title); }}
    .experiment-card {{ --category-accent: var(--semantic-experiment); --category-bg: var(--semantic-experiment-bg); --category-title: #6e4000; }}
    .experiment-card > .category-header {{ cursor: default; }}
    .experiment-header {{ display: grid; grid-template-columns: minmax(220px, 1fr) minmax(240px, 1.3fr); gap: 10px; align-items: end; }}
    .summary-field {{ font-size: 12px; font-weight: 800; }}
    .summary-field input, .summary-field select {{ width: 100%; box-sizing: border-box; }}
    .experiment-details {{ border-top: 1px solid #d8dde3; background: #ffffff; }}
    .experiment-details > summary {{ padding: 9px 10px; background: #fff8e8; color: #6e4000; font-size: 14px; font-weight: 800; }}
    .experiment-details:not([open]) > summary {{ border-bottom: 0; }}
    .experiment-command-grid {{ display: grid; gap: 10px; padding: 10px; background: #faf7f0; }}
    .command-block {{ --command-accent: var(--semantic-neutral); --command-bg: #f4f7fa; --command-title: #343a40; border: 1px solid #dce3ea; border-left: 3px solid var(--command-accent); background: #ffffff; }}
    .command-network {{ --command-accent: #2f6fae; --command-bg: #e8f1fb; --command-title: #173e66; }}
    .command-balance {{ --command-accent: #2c7a5a; --command-bg: #e9f6f1; --command-title: #18583f; }}
    .command-path {{ --command-accent: #5b5fa8; --command-bg: #eef0fb; --command-title: #393d75; }}
    .command-route {{ --command-accent: #6d5a8f; --command-bg: #f0edf8; --command-title: #453765; }}
    .command-invoice {{ --command-accent: #a26000; --command-bg: #fff3d7; --command-title: #6e4000; }}
    .command-outputs {{ --command-accent: #4f5d75; --command-bg: #eef2f6; --command-title: #2f3c4f; }}
    .command-title {{ display: flex; justify-content: space-between; gap: 12px; margin: 0; padding: 8px 10px; background: var(--command-bg); color: var(--command-title); border-bottom: 1px solid #e2e6ea; font-weight: 800; font-size: 13px; cursor: pointer; }}
    details.command-block:not([open]) > .command-title {{ border-bottom: 0; }}
    .command-title code {{ font-size: 11px; font-weight: 700; color: var(--command-accent); background: #ffffff; border: 1px solid #dce3ea; padding: 1px 4px; }}
    .command-fields {{ display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; padding: 10px; }}
    .command-note {{ margin: 0; padding: 10px; background: #fbfcfd; }}
    .notice {{ margin: 0 0 12px; padding: 9px; background: #fff8e6; border: 1px solid #e2c56f; color: #4d3b00; font-weight: 700; }}
    .error {{ margin: 0 0 12px; padding: 9px; background: #fdeaea; border: 1px solid #e2a0a0; color: #7b1d1d; font-weight: 700; }}
    .muted {{ color: #5d666f; font-size: 13px; font-weight: 400; }}
    .figure {{ height: 560px; }}
    pre {{ white-space: pre-wrap; background: #111820; color: #e6edf3; padding: 12px; overflow: auto; }}
    #dse-json-preview {{ background: #111b27; }}
    pre[data-run-output] {{ background: #0f1d1a; }}
    @media (max-width: 760px) {{
      .grid, .row, .row3, .experiment-header, .command-fields, .path-field.multi-action {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
  <header><a href="/">UV DSE Wizard</a><h1>{escape(title)}</h1></header>
  <main>{message_html}{error_html}{selector_notice}{body}</main>
  <script>
  (() => {{
    const WIZARD_TOKEN = "{SESSION_TOKEN}";

    function parseDseValues(text) {{
      const cleaned = (text || "").trim();
      if (!cleaned) return [];
      try {{
        const parsed = JSON.parse("[" + cleaned + "]");
        if (Array.isArray(parsed)) return parsed;
      }} catch (error) {{
        if (cleaned.includes('"')) {{
          throw new Error("Invalid DSE value list. Quote comma-containing values with JSON double quotes.");
        }}
      }}
      return cleaned.split(",").map((item) => item.trim()).filter(Boolean).map((item) => {{
        try {{
          return JSON.parse(item);
        }} catch (_error) {{
          return item;
        }}
      }});
    }}

    function countValues(text) {{
      try {{
        return parseDseValues(text).length;
      }} catch (_error) {{
        return 0;
      }}
    }}

    function updateSpaceCount() {{
      const rows = Array.from(document.querySelectorAll("[data-dse-row]"));
      let selected = 0;
      let total = 1;
      for (const row of rows) {{
        const checkbox = row.querySelector("[data-dse-include]");
        const values = row.querySelector("[data-dse-values]");
        if (!checkbox || !values || !checkbox.checked) continue;
        const valueCount = countValues(values.value);
        if (valueCount <= 0) {{
          total = 0;
        }} else if (total !== 0) {{
          total *= valueCount;
        }}
        selected += 1;
      }}
      if (selected === 0) total = 0;
      const selectedNode = document.getElementById("selected-parameter-count");
      const totalNode = document.getElementById("parameter-space-size");
      if (selectedNode) selectedNode.textContent = String(selected);
      if (totalNode) totalNode.textContent = String(total);
    }}

    async function selectNativePath(button) {{
      const target = document.getElementById(button.dataset.browseTarget);
      if (!target) return;
      const previousText = button.textContent;
      button.disabled = true;
      button.textContent = "Selecting...";
      try {{
        const params = new URLSearchParams({{
          mode: button.dataset.browseMode || "file",
          path: target.value || ".",
          token: WIZARD_TOKEN
        }});
        const response = await fetch("/api/select-path?" + params.toString());
        const data = await response.json();
        if (data.path) {{
          target.value = data.path;
          target.dispatchEvent(new Event("input", {{ bubbles: true }}));
          const action = button.dataset.browseAction || "";
          if (action) submitWizardForm(button, action);
        }} else if (data.error) {{
          window.alert(data.error);
        }}
      }} catch (error) {{
        window.alert("Could not open the native file selector: " + error.message);
      }} finally {{
        button.disabled = false;
        button.textContent = previousText;
      }}
    }}

    function submitWizardForm(button, action) {{
      const form = button.closest("form");
      if (!form) return;
      form.action = action;
      form.method = "post";
      if (form.requestSubmit) form.requestSubmit();
      else form.submit();
    }}

    function updateJsonPreview() {{
      const preview = document.getElementById("dse-json-preview");
      const status = document.getElementById("dse-json-preview-status");
      if (!preview) return;
      const parameters = {{}};
      const errors = [];
      for (const row of document.querySelectorAll("[data-dse-row]")) {{
        const checkbox = row.querySelector("[data-dse-include]");
        const nameInput = row.querySelector('input[name^="name_"]');
        const valuesInput = row.querySelector("[data-dse-values]");
        if (!checkbox || !nameInput || !valuesInput || !checkbox.checked) continue;
        try {{
          const values = parseDseValues(valuesInput.value);
          if (values.length === 0) {{
            errors.push(nameInput.value + " has no values");
          }} else {{
            parameters[nameInput.value] = values;
          }}
        }} catch (error) {{
          errors.push(nameInput.value + ": " + error.message);
        }}
      }}

      let experiment = {{}};
      try {{
        experiment = buildExperimentFromForm();
      }} catch (error) {{
        experiment = {{}};
        errors.push("experiment: " + error.message);
      }}

      preview.textContent = JSON.stringify({{ parameters, experiment }}, null, 2);
      if (status) {{
        const selected = Object.keys(parameters).length;
        const sizeNode = document.getElementById("parameter-space-size");
        const size = sizeNode ? sizeNode.textContent : "0";
        status.textContent = errors.length
          ? "Preview has errors: " + errors.join("; ")
          : selected + " selected parameters, " + size + " configurations";
      }}
    }}

    function experimentField(row, prefix) {{
      const input = row.querySelector('[name^="' + prefix + '"]');
      return input ? input.value.trim() : "";
    }}

    function experimentChecked(row, prefix) {{
      const input = row.querySelector('[name^="' + prefix + '"]');
      return Boolean(input && input.checked);
    }}

    function parseRequiredInt(value, label) {{
      if (!value) throw new Error(label + " is required");
      const parsed = Number.parseInt(value, 10);
      if (!Number.isFinite(parsed) || String(parsed) !== String(Number(value))) {{
        throw new Error(label + " must be an integer");
      }}
      return parsed;
    }}

    function parseOptionalInt(value, label) {{
      if (!value) return null;
      return parseRequiredInt(value, label);
    }}

    function parseRequiredFloat(value, label) {{
      if (!value) throw new Error(label + " is required");
      const parsed = Number.parseFloat(value);
      if (!Number.isFinite(parsed)) throw new Error(label + " must be numeric");
      return parsed;
    }}

    function buildExperimentFromForm() {{
      const row = document.querySelector("[data-experiment-row]");
      if (!row) throw new Error("experiment form is missing");
      const name = experimentField(row, "exp_name_") || "experiment";
      const kind = experimentField(row, "exp_kind_") || "invoice";
      const balance = kind === "bootstrap" ? "none" : (experimentField(row, "exp_balance_") || "none");
      const pathFinderByKind = {{
        path: experimentField(row, "exp_path_path_finder_") || "lnd",
        route: experimentField(row, "exp_route_path_finder_") || "lnd",
        invoice: experimentField(row, "exp_inv_path_finder_") || "lnd",
        bootstrap: "lnd"
      }};
      const pathFinder = pathFinderByKind[kind] || "lnd";
      if (pathFinder === "all" && kind !== "path") throw new Error(name + " can use path_finder=all only for path experiments");

      const bootMode = experimentField(row, "exp_boot_mode_") || "scratch";
      const commands = [];
      if (bootMode === "load") {{
        const bootFile = experimentField(row, "exp_boot_file_");
        if (!bootFile) throw new Error(name + " boot snapshot file is required");
        commands.push({{ command: "boot", mode: "load", file: bootFile }});
      }} else {{
        commands.push("boot");
      }}
      const minDelta = parseOptionalInt(experimentField(row, "exp_min_delta_"), name + " min_delta");
      if (balance === "rndbal") {{
        commands.push(minDelta === null ? "rndbal" : {{ command: "rndbal", min_delta: minDelta }});
      }} else if (balance === "bal") {{
        const command = {{ command: "bal", level: parseRequiredFloat(experimentField(row, "exp_bal_level_"), name + " balance level") }};
        if (minDelta !== null) command.min_delta = minDelta;
        commands.push(command);
      }}

      if (kind === "path") {{
        commands.push({{
          command: "path",
          start: experimentField(row, "exp_path_start_") || "pk0",
          destination: experimentField(row, "exp_path_destination_") || "pk7",
          amount: parseRequiredInt(experimentField(row, "exp_path_amount_"), name + " path amount"),
          path_finder: pathFinder,
          topk: parseRequiredInt(experimentField(row, "exp_path_topk_"), name + " topk")
        }});
      }} else if (kind === "route") {{
        const routeCommand = {{
          command: "route",
          sender: experimentField(row, "exp_route_sender_") || "pk0",
          destination: experimentField(row, "exp_route_destination_") || "pk7",
          amount: parseRequiredInt(experimentField(row, "exp_route_amount_"), name + " route amount"),
          max_fees: parseRequiredInt(experimentField(row, "exp_route_max_fees_"), name + " route max_fees"),
          path_finder: pathFinder
        }};
        const message = experimentField(row, "exp_route_message_");
        if (message) routeCommand.message = message;
        commands.push(routeCommand);
      }} else if (kind === "invoice") {{
        commands.push({{
          command: "inv",
          node_events_per_block: parseRequiredFloat(experimentField(row, "exp_inv_node_events_per_block_"), name + " node_events_per_block"),
          blocks: parseRequiredInt(experimentField(row, "exp_inv_blocks_"), name + " blocks"),
          min_amt: parseRequiredInt(experimentField(row, "exp_inv_min_amt_"), name + " min_amt"),
          max_amt: parseRequiredInt(experimentField(row, "exp_inv_max_amt_"), name + " max_amt"),
          max_fees: parseRequiredInt(experimentField(row, "exp_inv_max_fees_"), name + " max_fees"),
          path_finder: pathFinder
        }});
      }}

      const outputs = [];
      if (experimentChecked(row, "exp_output_network_")) outputs.push("network");
      if (experimentChecked(row, "exp_output_invoice_")) outputs.push("invoice");
      return {{ name, commands, outputs: outputs.length ? outputs : ["network"] }};
    }}

    function updateExperimentVisibility() {{
      for (const row of document.querySelectorAll("[data-experiment-row]")) {{
        const kind = experimentField(row, "exp_kind_") || "invoice";
        for (const block of row.querySelectorAll("[data-command-block]")) {{
          const hidden = block.dataset.commandBlock !== kind;
          block.hidden = hidden;
          if (hidden) block.removeAttribute("open");
        }}
        const balanceBlock = row.querySelector("[data-balance-block]");
        if (balanceBlock) {{
          const hidden = kind === "bootstrap";
          balanceBlock.hidden = hidden;
          if (hidden) balanceBlock.removeAttribute("open");
        }}
        const bootFileBlock = row.querySelector("[data-boot-file-block]");
        if (bootFileBlock) {{
          bootFileBlock.hidden = (experimentField(row, "exp_boot_mode_") || "scratch") !== "load";
        }}
      }}
    }}

    function commandBlockHasValues(row, kind) {{
      const block = row.querySelector('[data-command-block="' + kind + '"]');
      if (!block) return false;
      return Array.from(block.querySelectorAll("input, select, textarea")).some((field) => {{
        if (field.type === "checkbox") return field.checked;
        return String(field.value || "").trim() !== "";
      }});
    }}

    function confirmRecipeChange(select) {{
      const previous = select.dataset.previousKind || select.defaultValue || select.value;
      if (previous === select.value) return true;
      const row = select.closest("[data-experiment-row]");
      if (row && commandBlockHasValues(row, previous)) {{
        const ok = window.confirm(
          "Changing recipe hides the current command from the saved DSE JSON. " +
          "The values stay in this form if you switch back. Continue?"
        );
        if (!ok) {{
          select.value = previous;
          return false;
        }}
      }}
      select.dataset.previousKind = select.value;
      return true;
    }}

    document.addEventListener("click", (event) => {{
      const button = event.target.closest("[data-browse-target]");
      if (button) {{
        event.preventDefault();
        selectNativePath(button);
        return;
      }}
      const refreshPreview = event.target.closest("#refresh-json-preview");
      if (refreshPreview) {{
        event.preventDefault();
        updateSpaceCount();
        updateJsonPreview();
      }}
    }});

    document.addEventListener("input", (event) => {{
      if (event.target.matches("[data-dse-values]")) updateSpaceCount();
    }});
    document.addEventListener("change", (event) => {{
      if (event.target.matches("[data-dse-include]")) updateSpaceCount();
      if (event.target.matches("[name^='exp_kind_']")) {{
        if (!confirmRecipeChange(event.target)) {{
          updateExperimentVisibility();
          return;
        }}
        updateExperimentVisibility();
        return;
      }}
      if (event.target.matches("[name^='exp_boot_mode_'], [name^='exp_balance_']")) updateExperimentVisibility();
    }});
    function updateRunStatus() {{
      const panel = document.querySelector("[data-run-job]");
      if (!panel) return;
      const jobId = panel.dataset.runJob;
      const output = panel.querySelector("[data-run-output]");
      const status = panel.querySelector("[data-run-status]");
      fetch("/api/run-status?" + new URLSearchParams({{ job_id: jobId, token: WIZARD_TOKEN }}).toString())
        .then((response) => response.json())
        .then((data) => {{
          if (data.error) {{
            status.textContent = data.error;
            return;
          }}
          status.textContent = "Status: " + data.status + "; exit code: " + (data.return_code ?? "running") + "; elapsed: " + data.elapsed_seconds + "s";
          output.textContent = data.output || "";
          if (!data.done) window.setTimeout(updateRunStatus, 1000);
        }})
        .catch((error) => {{
          status.textContent = "Could not read run status: " + error.message;
        }});
    }}
    updateSpaceCount();
    for (const select of document.querySelectorAll('[name^="exp_kind_"]')) {{
      select.dataset.previousKind = select.value;
    }}
    updateExperimentVisibility();
    updateJsonPreview();
    updateRunStatus();
  }})();
  </script>
</body>
</html>""".encode("utf-8")


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def redirect(location: str) -> tuple[int, list[tuple[str, str]], bytes]:
    return 303, [("Location", location)], b""


def token_input() -> str:
    return f'<input type="hidden" name="token" value="{escape(SESSION_TOKEN)}">'


def token_query() -> str:
    return urlencode({"token": SESSION_TOKEN})


def render_home() -> bytes:
    body = """
<section class="grid">
  <div class="card card-create">
    <h2>Create DSE JSON</h2>
    <p class="muted">Build or load a DSE JSON by selecting parameters from a properties file and defining one experiment.</p>
    <a href="/create">Open</a>
  </div>
  <div class="card card-run">
    <h2>Run DSE</h2>
    <p class="muted">Configure and launch the existing DSE runner from the GUI.</p>
    <a href="/run">Open</a>
  </div>
  <div class="card card-visualize">
    <h2>Open DSE Visualizer</h2>
    <p class="muted">Preview DSE reports and export bar, line, scatter, or pie PDFs.</p>
    <a href="/visualize">Open</a>
  </div>
</section>
"""
    return page("Main Menu", body)


def path_control(label: str, name: str, value: str, mode: str, browse_action: str = "") -> str:
    safe_name = escape(name)
    action_attr = f' data-browse-action="{escape(browse_action)}"' if browse_action else ""
    return f"""
<label>{escape(label)}
  <div class="path-field">
    <input id="{safe_name}" class="path-input" name="{safe_name}" value="{escape(value)}">
    <button type="button" class="secondary" data-browse-target="{safe_name}" data-browse-mode="{escape(mode)}"{action_attr}>Browse</button>
  </div>
</label>
"""


def dse_json_file_control(value: str) -> str:
    return f"""
<label>Current DSE JSON
  <div class="path-field multi-action">
    <input id="dse_json_path" class="path-input" name="dse_json_path" value="{escape(value)}">
    <button type="button" class="secondary" data-browse-target="dse_json_path" data-browse-mode="json" data-browse-action="/create/load">Load</button>
    <button type="button" class="secondary" data-browse-target="dse_json_path" data-browse-mode="save_json" data-browse-action="/create/save">Save</button>
  </div>
</label>
"""


def render_experiment_editor(row: dict[str, str]) -> str:
    kind = row.get("kind", "invoice")
    boot_mode = row.get("boot_mode", "scratch")
    boot_file_hidden = " hidden" if boot_mode != "load" else ""
    balance_hidden = " hidden" if kind == "bootstrap" else ""
    path_hidden = "" if kind == "path" else " hidden"
    route_hidden = "" if kind == "route" else " hidden"
    invoice_hidden = "" if kind == "invoice" else " hidden"
    output_network = " checked" if row.get("output_network") == "on" else ""
    output_invoice = " checked" if row.get("output_invoice") == "on" else ""
    return f"""
<section class="category experiment-card" data-experiment-row>
  <div class="category-header experiment-header">
    <label class="summary-field">Name
      <input name="exp_name_0" data-experiment-field value="{escape(row.get("name", ""))}">
    </label>
    <label class="summary-field">Recipe
      {select_html("exp_kind_0", list(EXPERIMENT_KIND_LABELS), kind, EXPERIMENT_KIND_LABELS)}
    </label>
  </div>
  <details class="experiment-details" open>
    <summary>Command configuration</summary>
    <div class="experiment-command-grid">
      <details class="command-block command-network">
        <summary class="command-title"><span>Network source</span><code>boot</code></summary>
        <div class="command-fields">
          <label>Source
            {select_html("exp_boot_mode_0", list(BOOT_MODE_LABELS), boot_mode, BOOT_MODE_LABELS)}
          </label>
          <label data-boot-file-block{boot_file_hidden}>Snapshot .dat file
            <div class="path-field">
              <input id="exp_boot_file_0" name="exp_boot_file_0" data-experiment-field value="{escape(row.get("boot_file", ""))}">
              <button type="button" class="secondary" data-browse-target="exp_boot_file_0" data-browse-mode="dat">Browse</button>
            </div>
          </label>
        </div>
      </details>

    <details class="command-block command-balance" data-balance-block{balance_hidden}>
      <summary class="command-title"><span>Balance command</span><code>bal / rndbal</code></summary>
      <div class="command-fields">
        <label>Balance setup
          {select_html("exp_balance_0", list(BALANCE_MODE_LABELS), row.get("balance", "none"), BALANCE_MODE_LABELS)}
        </label>
        <label>Balance level for bal
          <input name="exp_bal_level_0" data-experiment-field value="{escape(row.get("bal_level", ""))}">
        </label>
        <label>Min balance delta
          <input name="exp_min_delta_0" data-experiment-field value="{escape(row.get("min_delta", ""))}">
        </label>
      </div>
    </details>

    <details class="command-block command-path" data-command-block="path"{path_hidden}>
      <summary class="command-title"><span>Path finding command</span><code>path</code></summary>
      <div class="command-fields">
        <label>Start node
          <input name="exp_path_start_0" data-experiment-field value="{escape(row.get("path_start", ""))}">
        </label>
        <label>Destination node
          <input name="exp_path_destination_0" data-experiment-field value="{escape(row.get("path_destination", ""))}">
        </label>
        <label>Amount
          <input name="exp_path_amount_0" data-experiment-field value="{escape(row.get("path_amount", ""))}">
        </label>
        <label>Path finder
          {select_html("exp_path_path_finder_0", PATH_FINDER_OPTIONS, row.get("path_path_finder", "lnd"))}
        </label>
        <label>Top K paths
          <input name="exp_path_topk_0" data-experiment-field value="{escape(row.get("path_topk", ""))}">
        </label>
      </div>
    </details>

    <details class="command-block command-route" data-command-block="route"{route_hidden}>
      <summary class="command-title"><span>Single payment command</span><code>route</code></summary>
      <div class="command-fields">
        <label>Sender node
          <input name="exp_route_sender_0" data-experiment-field value="{escape(row.get("route_sender", ""))}">
        </label>
        <label>Destination node
          <input name="exp_route_destination_0" data-experiment-field value="{escape(row.get("route_destination", ""))}">
        </label>
        <label>Amount
          <input name="exp_route_amount_0" data-experiment-field value="{escape(row.get("route_amount", ""))}">
        </label>
        <label>Max fees
          <input name="exp_route_max_fees_0" data-experiment-field value="{escape(row.get("route_max_fees", ""))}">
        </label>
        <label>Path finder
          {select_html("exp_route_path_finder_0", [option for option in PATH_FINDER_OPTIONS if option != "all"], row.get("route_path_finder", "lnd"))}
        </label>
        <label>Route message
          <input name="exp_route_message_0" data-experiment-field value="{escape(row.get("route_message", ""))}">
        </label>
      </div>
    </details>

    <details class="command-block command-invoice" data-command-block="invoice"{invoice_hidden}>
      <summary class="command-title"><span>Invoice campaign command</span><code>inv</code></summary>
      <div class="command-fields">
        <label>Node events / block
          <input name="exp_inv_node_events_per_block_0" data-experiment-field value="{escape(row.get("inv_node_events_per_block", ""))}">
        </label>
        <label>Blocks
          <input name="exp_inv_blocks_0" data-experiment-field value="{escape(row.get("inv_blocks", ""))}">
        </label>
        <label>Min invoice amount
          <input name="exp_inv_min_amt_0" data-experiment-field value="{escape(row.get("inv_min_amt", ""))}">
        </label>
        <label>Max invoice amount
          <input name="exp_inv_max_amt_0" data-experiment-field value="{escape(row.get("inv_max_amt", ""))}">
        </label>
        <label>Max fees
          <input name="exp_inv_max_fees_0" data-experiment-field value="{escape(row.get("inv_max_fees", ""))}">
        </label>
        <label>Path finder
          {select_html("exp_inv_path_finder_0", [option for option in PATH_FINDER_OPTIONS if option != "all"], row.get("inv_path_finder", "lnd"))}
        </label>
      </div>
    </details>

    <details class="command-block command-outputs">
      <summary class="command-title"><span>Report outputs</span><code>outputs</code></summary>
      <div class="actions">
        <label><span><input type="checkbox" name="exp_output_network_0" data-experiment-output{output_network}> Network stats</span></label>
        <label><span><input type="checkbox" name="exp_output_invoice_0" data-experiment-output{output_invoice}> Invoice report</span></label>
      </div>
    </details>
    </div>
  </details>
</section>
"""


def render_create(
    query: dict[str, str],
    message: str = "",
    error: str = "",
    *,
    state: WizardState | None = None,
) -> bytes:
    if state is None:
        state = default_create_state(query, message or query.get("message", ""), error or query.get("error", ""))
    parameter_names = sorted(set(state.parameters) | set(state.selected_parameters), key=parameter_sort_key)
    experiment = state.experiment
    experiment_row = state.experiment_row if state.experiment_row is not None else experiment_row_for_render(experiment)
    selected_count, space_size = selected_space_size(state.selected_parameters)
    preview_json = json.dumps({"parameters": state.selected_parameters, "experiment": experiment}, indent=2)

    grouped_rows: dict[str, list[str]] = {}
    for index, name in enumerate(parameter_names):
        value = state.parameters.get(name, "")
        selected_values = state.selected_parameters.get(name)
        checked = " checked" if selected_values is not None else ""
        values_text = state.value_text_overrides.get(
            name,
            stringify_values(selected_values, quote_strings=True) if selected_values is not None else default_value_text(value),
        )
        category = parameter_category(name) if name in state.parameters else "DSE JSON only"
        grouped_rows.setdefault(category, []).append(
            f"<tr data-dse-row>"
            f"<td><input type=\"checkbox\" name=\"include_{index}\" data-dse-include{checked}></td>"
            f"<td><code>{escape(name)}</code><input type=\"hidden\" name=\"name_{index}\" value=\"{escape(name)}\"></td>"
            f"<td>{escape(value or 'not present in loaded properties')}</td>"
            f"<td><input name=\"values_{index}\" data-dse-values value=\"{escape(values_text)}\"></td>"
            f"</tr>"
        )

    category_html = []
    for category in PARAMETER_CATEGORY_ORDER:
        rows = grouped_rows.pop(category, [])
        if not rows:
            continue
        category_class = category_style_class(category)
        category_html.append(
            f"""
<details class="category {category_class}">
  <summary class="category-header">
    <h3>{escape(category)}</h3>
    <span class="muted">{len(rows)} parameters</span>
  </summary>
  <table>
    <thead><tr><th>Use</th><th>Parameter</th><th>Base value</th><th>DSE values</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table>
</details>
"""
        )
    for category, rows in sorted(grouped_rows.items()):
        category_class = category_style_class(category)
        category_html.append(
            f"""
<details class="category {category_class}">
  <summary class="category-header">
    <h3>{escape(category)}</h3>
    <span class="muted">{len(rows)} parameters</span>
  </summary>
  <table>
    <thead><tr><th>Use</th><th>Parameter</th><th>Base value</th><th>DSE values</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table>
</details>
"""
        )

    body = f"""
<form id="create-save-form" method="post" action="/create/save">
  {token_input()}
  <details class="panel panel-files">
    <summary><h2>DSE JSON files</h2></summary>
    <div class="disclosure-body">
    <div class="row3">
      {path_control("Properties file", "properties_path", state.properties_path, "properties")}
      {dse_json_file_control(state.dse_json_path)}
    </div>
    <div class="actions">
      <button type="submit" class="secondary" formaction="/create/refresh">Reload properties</button>
    </div>
    </div>
  </details>

  <details class="panel panel-experiment">
    <summary><h2>Experiment section of the DSE JSON</h2></summary>
    <div class="disclosure-body">
    <p class="muted">Define the one experiment that will run for each generated parameter-space configuration. Choose the command recipe, network source, command parameters, and report outputs.</p>
    {render_experiment_editor(experiment_row)}
    </div>
  </details>

  <details class="panel panel-parameters">
    <summary><h2>Parameter space section of the DSE JSON</h2></summary>
    <div class="disclosure-body">
    <div class="space-summary">
      <span><strong id="parameter-space-size">{space_size}</strong> configurations</span>
      <span><strong id="selected-parameter-count">{selected_count}</strong> selected parameters</span>
      <span class="muted">Use commas to separate DSE alternatives. Quote one value when it contains commas, for example "0,100,1000".</span>
      <button type="button" class="secondary" id="refresh-json-preview">Refresh Preview</button>
    </div>
    <input type="hidden" name="parameter_count" value="{len(parameter_names)}">
    {"".join(category_html)}
    </div>
  </details>

  <details class="panel panel-preview">
    <summary><h2>Resulting DSE JSON</h2></summary>
    <div class="disclosure-body">
    <p id="dse-json-preview-status" class="muted">{selected_count} selected parameters, {space_size} configurations</p>
    <pre id="dse-json-preview">{escape(preview_json)}</pre>
    </div>
  </details>
</form>
"""
    return page("Create DSE JSON", body, state.message, state.error)

def render_run(query: dict[str, str], message: str = "", error: str = "") -> bytes:
    force_checked = " checked" if query.get("force") == "1" else ""
    force_confirm_checked = " checked" if query.get("force_confirm") == "1" else ""
    job_id = query.get("job_id", "")
    job_panel = ""
    if job_id:
        job = get_run_job(job_id)
        initial_status = job.status if job else "unknown"
        initial_output = job.output if job else ""
        job_panel = f"""
<section class="panel panel-run" data-run-job="{escape(job_id)}">
  <h2>DSE run</h2>
  <p class="muted" data-run-status>Status: {escape(initial_status)}</p>
  <form method="post" action="/run/cancel" class="actions">
    {token_input()}
    <input type="hidden" name="job_id" value="{escape(job_id)}">
    <button type="submit" class="secondary">Cancel Run</button>
  </form>
  <pre data-run-output>{escape(initial_output)}</pre>
</section>
"""
    body = f"""
<form method="post" action="/run/start" class="panel panel-run">
  {token_input()}
  <div class="row">
    {path_control("Base properties", "base_properties", query.get("base_properties", DEFAULT_PROPERTIES), "properties")}
    {path_control("DSE JSON", "dse_json", query.get("dse_json", DEFAULT_DSE_JSON), "json")}
    {path_control("Output directory", "output_dir", query.get("output_dir", DEFAULT_RUN_DIR), "dir")}
    <label>Limit
      <input name="limit" value="{escape(query.get("limit", ""))}">
    </label>
  </div>
  <label><span><input type="checkbox" name="force"{force_checked}> Replace output directory if it already exists</span></label>
  <label><span><input type="checkbox" name="force_confirm"{force_confirm_checked}> I understand replacement can delete the selected output directory contents</span></label>
  <div class="actions"><button type="submit">Start DSE Run</button></div>
</form>
{job_panel}
"""
    return page("Run DSE", body, message, error)


def render_visualize(query: dict[str, str], error: str = "") -> bytes:
    dataset: visualizer.DseDataset | None = None
    data: visualizer.ChartData | None = None
    spec: visualizer.ChartSpec | None = None
    current_error = error

    input_dir = query.get("input_dir", DEFAULT_RUN_DIR)
    report = query.get("report", "network")
    if report not in {"network", "invoice"}:
        report = "network"
    try:
        dataset = load_wizard_dataset(input_dir)
        spec = build_visualizer_spec(dataset, query, report)
        data = visualizer.prepare_chart_data(dataset, spec)
    except Exception as exc:
        current_error = str(exc)

    metrics = visualizer.available_metrics(report)
    metric_labels = {metric.key: metric.label for metric in metrics}
    selected_metric = spec.metric_key if spec else query.get("metric", visualizer.default_metric(report))
    x_param = spec.x_param if spec else query.get("x_param", "")
    experiment = spec.experiment if spec else query.get("experiment", "All")
    graph = spec.graph_type if spec else query.get("graph", "bar")
    aggregation = spec.aggregation if spec else query.get("aggregation", "mean")
    title = spec.title if spec else query.get("title", "")
    filter_text = query.get("filter", "")
    font_size = spec.font_size if spec else visualizer.parse_int(query.get("font_size"), 10)
    parameter_options = dataset.parameter_names if dataset else []
    experiment_options = ["All"] + dataset.experiments if dataset else ["All"]
    figure = visualizer.svg_chart(data, graph) if data else empty_svg()
    context = context_html(data.parameter_summary if data else [])
    warnings = warning_html(data.warnings if data else [])
    pdf_query = urlencode(
        {
            "input_dir": input_dir,
            "report": report,
            "metric": selected_metric,
            "x_param": x_param,
            "experiment": experiment,
            "graph": graph,
            "aggregation": aggregation,
            "filter": filter_text,
            "title": title,
            "font_size": str(font_size),
            "token": SESSION_TOKEN,
        }
    )

    body = f"""
<form method="get" action="/visualize" class="panel panel-visualize">
  <div class="row">
    {path_control("DSE output", "input_dir", input_dir, "dir")}
    <label>Report
      {select_html("report", ["network", "invoice"], report)}
    </label>
    <label>Metric
      {select_html("metric", [metric.key for metric in metrics], selected_metric, metric_labels)}
    </label>
    <label>X parameter
      {select_html("x_param", parameter_options, x_param)}
    </label>
  </div>
  <div class="row">
    <label>Experiment
      {select_html("experiment", experiment_options, experiment)}
    </label>
    <label>Graph
      {select_html("graph", visualizer.GRAPH_TYPES, graph)}
    </label>
    <label>Aggregation
      {select_html("aggregation", visualizer.AGGREGATIONS, aggregation)}
    </label>
    <label>PDF font size
      <input name="font_size" type="number" min="8" max="18" value="{font_size}">
    </label>
  </div>
  <div class="row3">
    <label>Fixed filters
      <input name="filter" value="{escape(filter_text)}">
    </label>
    <label>Title
      <input name="title" value="{escape(title)}">
    </label>
    <button type="submit">Preview</button>
  </div>
  <a class="button" href="/visualize/pdf?{escape(pdf_query)}">Create PDF</a>
</form>
<section class="row3">
  <div class="panel panel-visualize figure">{figure}</div>
  <div class="panel panel-preview">
    <h2>Parameter context</h2>
    {context}
    {warnings}
  </div>
</section>
"""
    return page("Open DSE Visualizer", body, error=current_error)


def build_visualizer_spec(dataset: visualizer.DseDataset, query: dict[str, str], report: str) -> visualizer.ChartSpec:
    return visualizer.chart_spec_from_params(dataset, query, report_type=report)


def select_html(name: str, options: list[str], selected: str, labels: dict[str, str] | None = None) -> str:
    labels = labels or {}
    option_html = []
    for option in options:
        selected_attr = " selected" if option == selected else ""
        option_html.append(f'<option value="{escape(option)}"{selected_attr}>{escape(labels.get(option, option))}</option>')
    if not option_html:
        option_html.append('<option value="">Load a DSE output first</option>')
    return f'<select name="{escape(name)}">{"".join(option_html)}</select>'


def context_html(lines: list[str]) -> str:
    if not lines:
        return "<p class=\"muted\">Load a DSE output directory and preview a figure.</p>"
    return "<ul>" + "".join(f"<li>{escape(line)}</li>" for line in lines) + "</ul>"


def warning_html(lines: list[str]) -> str:
    if not lines:
        return ""
    return '<div class="notice">Background variation is aggregated in this figure.</div>'


def empty_svg() -> str:
    return (
        '<svg viewBox="0 0 820 520" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">'
        '<rect width="100%" height="100%" fill="#ffffff"/>'
        '<text x="40" y="80" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="#4d5358">'
        'Load a DSE output directory to preview a figure.</text></svg>'
    )


class WizardHandler(BaseHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        query = {key: values[-1] for key, values in parse_qs(parsed.query).items()}
        if parsed.path == "/api/select-path":
            self.respond_json_with_token(query, lambda: native_select_path(query))
        elif parsed.path == "/api/run-status":
            self.respond_json_with_token(query, lambda: run_job_payload(query.get("job_id", "")))
        elif parsed.path == "/":
            self.respond(200, [], render_home())
        elif parsed.path == "/create":
            self.respond(200, [], render_create(query))
        elif parsed.path == "/run":
            self.respond(200, [], render_run(query, query.get("message", ""), query.get("error", "")))
        elif parsed.path == "/visualize":
            self.respond(200, [], render_visualize(query))
        elif parsed.path == "/visualize/pdf":
            self.respond_pdf(query)
        else:
            self.respond(404, [], page("Not Found", "<p>Unknown wizard page.</p>"))

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(length).decode("utf-8"))
        if self.path == "/create/save":
            try:
                require_token(form)
                output_path, _payload = save_dse_from_form(form)
                params = {
                    "properties_path": form_value(form, "properties_path", DEFAULT_PROPERTIES),
                    "dse_json_path": display_path(output_path),
                    "load_json": "1",
                    "message": f"Saved {display_path(output_path)}",
                }
                self.respond(*redirect("/create?" + urlencode(params)))
            except Exception as exc:
                state = create_state_from_form(form, error=str(exc))
                self.respond(200, [], render_create({}, state=state))
        elif self.path == "/create/load":
            try:
                require_token(form)
                params = create_redirect_params(form, load_json=True)
                self.respond(*redirect("/create?" + urlencode(params)))
            except Exception as exc:
                state = create_state_from_form(form, error=str(exc))
                self.respond(200, [], render_create({}, state=state))
        elif self.path == "/create/refresh":
            try:
                require_token(form)
                params = create_redirect_params(form, load_json=False)
                self.respond(*redirect("/create?" + urlencode(params)))
            except Exception as exc:
                state = create_state_from_form(form, error=str(exc))
                self.respond(200, [], render_create({}, state=state))
        elif self.path == "/run/start":
            try:
                require_token(form)
                job = start_run_job(form)
                query = dict(job.query)
                query["job_id"] = job.job_id
                self.respond(*redirect("/run?" + urlencode(query)))
            except Exception as exc:
                self.respond(200, [], render_run(build_run_query(form), error=error_page_text(exc)))
        elif self.path == "/run/cancel":
            try:
                require_token(form)
                job_id = form_value(form, "job_id", "")
                cancel_run_job(job_id)
                self.respond(*redirect("/run?" + urlencode({"job_id": job_id, "message": "Cancellation requested."})))
            except Exception as exc:
                self.respond(200, [], render_run({}, error=error_page_text(exc)))
        else:
            self.respond(404, [], page("Not Found", "<p>Unknown wizard action.</p>"))

    def respond_pdf(self, query: dict[str, str]) -> None:
        try:
            require_token(query)
            dataset = load_wizard_dataset(query.get("input_dir", DEFAULT_RUN_DIR))
            report = query.get("report", "network")
            if report not in {"network", "invoice"}:
                report = "network"
            spec = build_visualizer_spec(dataset, query, report)
            data = visualizer.prepare_chart_data(dataset, spec)
            with tempfile.NamedTemporaryFile(prefix="uv-dse-wizard.", suffix=".pdf", delete=False) as tmp:
                pdf_path = Path(tmp.name)
            visualizer.export_pdf(pdf_path, data, spec.graph_type, spec.font_size)
            content = pdf_path.read_bytes()
            pdf_path.unlink(missing_ok=True)
            headers = [
                ("Content-Type", "application/pdf"),
                ("Content-Disposition", f'attachment; filename="{visualizer.sanitize_filename(data.title)}.pdf"'),
            ]
            self.respond(200, headers, content)
        except Exception as exc:
            self.respond(200, [], render_visualize(query, str(exc)))

    def respond(self, status: int, headers: list[tuple[str, str]], body: bytes) -> None:
        self.send_response(status)
        if not any(key.lower() == "content-type" for key, _value in headers):
            self.send_header("Content-Type", "text/html; charset=utf-8")
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def respond_json(self, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        self.respond(200, [("Content-Type", "application/json; charset=utf-8")], body)

    def respond_json_with_token(self, query: dict[str, str], callback: object) -> None:
        try:
            require_token(query)
            payload = callback()
        except Exception as exc:
            payload = {"error": str(exc)}
        self.respond_json(payload)


def error_page_text(exc: Exception) -> str:
    return str(exc)


def create_redirect_params(form: dict[str, list[str]], *, load_json: bool) -> dict[str, str]:
    params = {
        "properties_path": form_value(form, "properties_path", DEFAULT_PROPERTIES),
        "dse_json_path": form_value(form, "dse_json_path", DEFAULT_DSE_JSON),
    }
    if load_json:
        params["load_json"] = "1"
    return params


def require_token(values: dict[str, str] | dict[str, list[str]]) -> None:
    token: str
    raw = values.get("token") if values else None
    if isinstance(raw, list):
        token = raw[-1] if raw else ""
    else:
        token = str(raw or "")
    if not secrets.compare_digest(token, SESSION_TOKEN):
        raise PermissionError("Invalid or missing wizard session token.")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Open the UltraViolet DSE browser wizard.")
    parser.add_argument("--host", default="127.0.0.1", help="Host for the local wizard server.")
    parser.add_argument("--port", type=int, default=0, help="Port for the local wizard server. Defaults to an available port.")
    parser.add_argument("--no-browser", action="store_true", help="Print the URL without opening a browser.")
    parser.add_argument("--allow-remote", action="store_true", help="Allow binding to a non-loopback host.")
    return parser


def main() -> int:
    args = build_arg_parser().parse_args()
    if not args.allow_remote and args.host not in {"127.0.0.1", "localhost", "::1"}:
        print("Refusing to bind the wizard to a non-loopback host without --allow-remote.", file=sys.stderr)
        return 1
    if args.allow_remote and args.host not in {"127.0.0.1", "localhost", "::1"}:
        print("WARNING: remote wizard access can run local DSE commands and write files.", file=sys.stderr, flush=True)
    server = ThreadingHTTPServer((args.host, args.port), WizardHandler)
    url = f"http://{server.server_address[0]}:{server.server_address[1]}/"
    print(f"Starting UV DSE Wizard at {url}", flush=True)
    print("Press Ctrl-C to stop the wizard server.", flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping UV DSE Wizard.", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
