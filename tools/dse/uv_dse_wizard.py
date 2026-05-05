#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import webbrowser
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

sys.dont_write_bytecode = True

import uv_dse_visualizer as visualizer

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_PROPERTIES = "../../uv_configs/template.properties"
DEFAULT_RUN_DIR = "dse_runs/quickstart"
DEFAULT_DSE_JSON = "quickstart_dse.json"
DEFAULT_SAVE_JSON = "dse_runs/tutorial/first_dse.json"


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

DEFAULT_EXPERIMENTS = [
    {
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
]


@dataclass(frozen=True)
class WizardState:
    properties_path: str
    dse_json_path: str
    save_path: str
    parameters: dict[str, str]
    selected_parameters: dict[str, list[object]]
    experiments: list[dict[str, object]]
    message: str = ""
    error: str = ""


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
        name = path.name if path.name and path.suffix else Path(DEFAULT_SAVE_JSON).name
        return name if name.endswith(".json") else f"{name}.json"
    return path.name


def dialog_title(mode: str) -> str:
    return {
        "properties": "Select UltraViolet properties file",
        "json": "Select DSE JSON file",
        "save_json": "Save DSE JSON file",
        "dir": "Select DSE output directory",
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


def load_properties_with_includes(path: Path) -> dict[str, str]:
    merged: dict[str, str] = {}
    load_properties_into(path.resolve(), merged, [])
    return merged


def load_properties_into(path: Path, merged: dict[str, str], stack: list[Path]) -> None:
    if path in stack:
        chain = " -> ".join(str(item) for item in stack + [path])
        raise ValueError(f"Circular properties include: {chain}")
    if not path.is_file():
        raise ValueError(f"Properties file not found: {path}")

    local: dict[str, str] = {}
    stack.append(path)
    try:
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            directive = parse_include_directive(line)
            if directive:
                for include_text in directive:
                    include_path = (path.parent / include_text).resolve()
                    load_properties_into(include_path, merged, stack)
                continue

            key, value = parse_property_line(line)
            if key:
                local[key] = value
    finally:
        stack.pop()
    merged.update(local)


def parse_include_directive(line: str) -> list[str] | None:
    for directive in ("@include", "@import"):
        if line.startswith(directive):
            remainder = line[len(directive):].strip()
            if remainder.startswith("=") or remainder.startswith(":"):
                remainder = remainder[1:].strip()
            if not remainder:
                raise ValueError(f"Missing path in {directive} directive")
            return [part.strip() for part in remainder.split(",") if part.strip()]
    return None


def parse_property_line(line: str) -> tuple[str, str]:
    separators = [index for index in (line.find("="), line.find(":")) if index >= 0]
    if not separators:
        return line.strip(), ""
    index = min(separators)
    return line[:index].strip(), line[index + 1:].strip()


def parse_json_values(text: str) -> list[object]:
    cleaned = text.strip()
    if not cleaned:
        return []
    try:
        parsed = json.loads(f"[{cleaned}]")
        if isinstance(parsed, list):
            return parsed
    except json.JSONDecodeError:
        pass

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


def stringify_values(values: list[object]) -> str:
    return ", ".join(stringify_value(value) for value in values)


def stringify_value(value: object) -> str:
    if isinstance(value, str):
        if "," in value or value.strip() != value or value == "":
            return json.dumps(value, separators=(",", ":"))
        return value
    return json.dumps(value, separators=(",", ":"))


def default_value_text(value: str) -> str:
    return stringify_values([value])


def load_dse_json(path: Path) -> tuple[dict[str, list[object]], list[dict[str, object]]]:
    if not path.is_file():
        raise ValueError(f"DSE JSON file not found: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("DSE JSON must be an object")
    parameters = payload.get("parameters", {})
    experiments = payload.get("experiments", DEFAULT_EXPERIMENTS)
    if not isinstance(parameters, dict):
        raise ValueError("'parameters' must be an object")
    if not isinstance(experiments, list):
        raise ValueError("'experiments' must be an array")
    normalized = {str(key): list(value) for key, value in parameters.items() if isinstance(value, list)}
    return normalized, experiments


def default_create_state(params: dict[str, str], message: str = "", error: str = "") -> WizardState:
    properties_path = params.get("properties_path", DEFAULT_PROPERTIES)
    dse_json_path = params.get("dse_json_path", DEFAULT_DSE_JSON)
    save_path = params.get("save_path", DEFAULT_SAVE_JSON)

    selected: dict[str, list[object]] = {}
    experiments = DEFAULT_EXPERIMENTS
    try:
        if params.get("load_json") and dse_json_path:
            selected, experiments = load_dse_json(resolve_tool_path(dse_json_path))
            message = f"Loaded {dse_json_path}"
    except Exception as exc:
        error = str(exc)

    try:
        properties = load_properties_with_includes(resolve_tool_path(properties_path))
    except Exception as exc:
        properties = {}
        error = str(exc)

    return WizardState(
        properties_path=properties_path,
        dse_json_path=dse_json_path,
        save_path=save_path,
        parameters=properties,
        selected_parameters=selected,
        experiments=experiments,
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


def selected_space_size(selected_parameters: dict[str, list[object]]) -> tuple[int, int]:
    non_empty = [values for values in selected_parameters.values() if values]
    if not non_empty:
        return 0, 0
    return len(non_empty), math.prod(len(values) for values in non_empty)


def save_dse_from_form(form: dict[str, list[str]]) -> tuple[Path, dict[str, object]]:
    count = int(form_value(form, "parameter_count", "0"))
    parameters: dict[str, list[object]] = {}
    for index in range(count):
        name = form_value(form, f"name_{index}", "")
        if not name or form_value(form, f"include_{index}", "") != "on":
            continue
        values = parse_json_values(form_value(form, f"values_{index}", ""))
        if not values:
            raise ValueError(f"Parameter '{name}' has no values")
        parameters[name] = values

    if not parameters:
        raise ValueError("Select at least one parameter")

    experiments_raw = form_value(form, "experiments_json", "")
    try:
        parsed_experiments = json.loads(experiments_raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid experiments JSON: line {exc.lineno}, column {exc.colno}: {exc.msg}") from exc
    if isinstance(parsed_experiments, dict) and "experiments" in parsed_experiments:
        experiments = parsed_experiments["experiments"]
    else:
        experiments = parsed_experiments
    if not isinstance(experiments, list) or not experiments:
        raise ValueError("Experiments JSON must be a non-empty array")

    payload = {"parameters": parameters, "experiments": experiments}
    save_path = resolve_tool_path(form_value(form, "save_path", DEFAULT_SAVE_JSON))
    save_path.parent.mkdir(parents=True, exist_ok=True)
    save_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return save_path, payload


def form_value(form: dict[str, list[str]], key: str, default: str = "") -> str:
    values = form.get(key)
    return values[-1] if values else default


def run_dse_from_form(form: dict[str, list[str]]) -> tuple[int, str]:
    command = [
        sys.executable,
        str(SCRIPT_DIR / "uv_dse_run"),
        str(resolve_tool_path(form_value(form, "base_properties", DEFAULT_PROPERTIES))),
        str(resolve_tool_path(form_value(form, "dse_json", DEFAULT_DSE_JSON))),
        "--output-dir",
        str(resolve_tool_path(form_value(form, "output_dir", DEFAULT_RUN_DIR))),
    ]
    if form_value(form, "force", "") == "on":
        command.append("--force")
    limit = form_value(form, "limit", "").strip()
    if limit:
        command.extend(["--limit", limit])

    completed = subprocess.run(command, cwd=SCRIPT_DIR, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return completed.returncode, completed.stdout


def page(title: str, body: str, message: str = "", error: str = "") -> bytes:
    message_html = f'<div class="notice">{escape(message)}</div>' if message else ""
    error_html = f'<div class="error">{escape(error)}</div>' if error else ""
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{escape(title)}</title>
  <style>
    body {{ margin: 0; font-family: Helvetica, Arial, sans-serif; color: #151719; background: #f3f5f7; }}
    header {{ background: #ffffff; border-bottom: 1px solid #d8dde3; padding: 14px 18px; display: flex; align-items: center; gap: 16px; }}
    header a {{ color: #1a5fa8; text-decoration: none; font-weight: 700; }}
    h1 {{ margin: 0; font-size: 20px; }}
    h2 {{ margin: 0 0 10px; font-size: 16px; }}
    main {{ padding: 16px 18px 28px; }}
    .grid {{ display: grid; grid-template-columns: repeat(3, minmax(220px, 1fr)); gap: 14px; }}
    .card, .panel {{ background: #ffffff; border: 1px solid #d8dde3; padding: 14px; }}
    .card a, button, .button {{ display: inline-block; border: 1px solid #174f91; background: #1a5fa8; color: #ffffff; padding: 8px 11px; font-weight: 700; text-decoration: none; cursor: pointer; }}
    form {{ display: grid; gap: 12px; }}
    label {{ display: grid; gap: 4px; font-size: 12px; font-weight: 700; color: #33383d; }}
    input, select, textarea {{ font: inherit; font-size: 13px; padding: 7px; border: 1px solid #b9c0c7; background: #ffffff; }}
    textarea {{ min-height: 180px; font-family: Menlo, Consolas, monospace; }}
    table {{ width: 100%; border-collapse: collapse; background: #ffffff; }}
    th, td {{ border-bottom: 1px solid #e2e6ea; padding: 6px; text-align: left; vertical-align: top; }}
    th {{ background: #f8f9fa; font-size: 12px; }}
    .row {{ display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr)); gap: 10px; align-items: end; }}
    .row3 {{ display: grid; grid-template-columns: 2fr 2fr 1fr; gap: 10px; align-items: end; }}
    .actions {{ display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }}
    .secondary {{ border-color: #9ba6b1; background: #ffffff; color: #26323c; }}
    .path-field {{ display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; }}
    .space-summary {{ display: flex; flex-wrap: wrap; gap: 14px; align-items: baseline; margin: 0 0 12px; }}
    .space-summary strong {{ font-size: 18px; }}
    .category {{ border: 1px solid #e2e6ea; margin-top: 10px; }}
    .category-header {{ display: flex; justify-content: space-between; gap: 12px; padding: 8px 10px; background: #f8f9fa; border-bottom: 1px solid #e2e6ea; }}
    .category-header h3 {{ margin: 0; font-size: 14px; }}
    .experiment-grid {{ display: grid; grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr); gap: 12px; }}
    .experiment-list {{ margin: 0; padding-left: 18px; }}
    .experiment-list li {{ margin: 0 0 8px; }}
    .experiment-list .muted {{ display: inline-block; white-space: pre-line; }}
    .notice {{ margin: 0 0 12px; padding: 9px; background: #fff8e6; border: 1px solid #e2c56f; color: #4d3b00; }}
    .error {{ margin: 0 0 12px; padding: 9px; background: #fdeaea; border: 1px solid #e2a0a0; color: #7b1d1d; }}
    .muted {{ color: #5d666f; font-size: 13px; }}
    .figure {{ height: 560px; }}
    pre {{ white-space: pre-wrap; background: #111820; color: #e6edf3; padding: 12px; overflow: auto; }}
  </style>
</head>
<body>
  <header><a href="/">UV DSE Wizard</a><h1>{escape(title)}</h1></header>
  <main>{message_html}{error_html}{body}</main>
  <script>
  (() => {{
    function countValues(text) {{
      const cleaned = (text || "").trim();
      if (!cleaned) return 0;
      try {{
        const parsed = JSON.parse("[" + cleaned + "]");
        return Array.isArray(parsed) ? parsed.length : 0;
      }} catch (_error) {{
        return cleaned.split(",").map((item) => item.trim()).filter(Boolean).length;
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

    function commandLabel(command) {{
      if (typeof command === "string") return command;
      if (command && typeof command === "object") return command.command || command.cmd || "command";
      return "command";
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
          path: target.value || "."
        }});
        const response = await fetch("/api/select-path?" + params.toString());
        const data = await response.json();
        if (data.path) {{
          target.value = data.path;
          target.dispatchEvent(new Event("input", {{ bubbles: true }}));
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

    function updateExperimentSummary() {{
      const textarea = document.getElementById("experiments_json");
      const summary = document.getElementById("experiments-summary");
      if (!textarea || !summary) return;
      summary.textContent = "";
      try {{
        const parsed = JSON.parse(textarea.value);
        const experiments = Array.isArray(parsed) ? parsed : parsed.experiments;
        if (!Array.isArray(experiments) || experiments.length === 0) {{
          summary.textContent = "No experiments configured.";
          return;
        }}
        const list = document.createElement("ul");
        list.className = "experiment-list";
        for (const experiment of experiments) {{
          const item = document.createElement("li");
          const name = document.createElement("strong");
          name.textContent = experiment.name || "experiment";
          const details = document.createElement("span");
          details.className = "muted";
          const commands = Array.isArray(experiment.commands) ? experiment.commands.map(commandLabel).join(" -> ") : "";
          const outputs = Array.isArray(experiment.outputs) ? experiment.outputs.join(", ") : (experiment.outputs || experiment.reports || "network");
          details.textContent = "\\ncommands: " + (commands || "none") + "\\noutputs: " + outputs;
          item.appendChild(name);
          item.appendChild(document.createElement("br"));
          item.appendChild(details);
          list.appendChild(item);
        }}
        summary.appendChild(list);
      }} catch (_error) {{
        summary.textContent = "Invalid experiments JSON.";
      }}
    }}

    document.addEventListener("click", (event) => {{
      const button = event.target.closest("[data-browse-target]");
      if (!button) return;
      event.preventDefault();
      selectNativePath(button);
    }});

    document.addEventListener("input", (event) => {{
      if (event.target.matches("[data-dse-values]")) updateSpaceCount();
      if (event.target.matches("#experiments_json")) updateExperimentSummary();
    }});
    document.addEventListener("change", (event) => {{
      if (event.target.matches("[data-dse-include]")) updateSpaceCount();
    }});
    updateSpaceCount();
    updateExperimentSummary();
  }})();
  </script>
</body>
</html>""".encode("utf-8")


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def redirect(location: str) -> tuple[int, list[tuple[str, str]], bytes]:
    return 303, [("Location", location)], b""


def render_home() -> bytes:
    body = """
<section class="grid">
  <div class="card">
    <h2>Create DSE JSON</h2>
    <p class="muted">Build or load a DSE JSON by selecting parameters from a properties file and defining experiments.</p>
    <a href="/create">Open</a>
  </div>
  <div class="card">
    <h2>Run DSE</h2>
    <p class="muted">Configure and launch the existing DSE runner from the GUI.</p>
    <a href="/run">Open</a>
  </div>
  <div class="card">
    <h2>Open DSE Visualizer</h2>
    <p class="muted">Preview DSE reports and export bar, line, scatter, or pie PDFs.</p>
    <a href="/visualize">Open</a>
  </div>
</section>
"""
    return page("Main Menu", body)


def path_control(label: str, name: str, value: str, mode: str) -> str:
    safe_name = escape(name)
    return f"""
<label>{escape(label)}
  <div class="path-field">
    <input id="{safe_name}" class="path-input" name="{safe_name}" value="{escape(value)}">
    <button type="button" class="secondary" data-browse-target="{safe_name}" data-browse-mode="{escape(mode)}">Browse</button>
  </div>
</label>
"""


def experiment_command_label(command: object) -> str:
    if isinstance(command, str):
        return command
    if isinstance(command, dict):
        return str(command.get("command", command.get("cmd", "command")))
    return "command"


def experiment_summary_html(experiments: list[dict[str, object]]) -> str:
    if not experiments:
        return '<p class="muted">No experiments configured.</p>'
    items = []
    for experiment in experiments:
        name = str(experiment.get("name", "experiment"))
        commands = experiment.get("commands", [])
        outputs = experiment.get("outputs", experiment.get("reports", []))
        if isinstance(commands, list):
            command_text = " -> ".join(experiment_command_label(command) for command in commands)
        else:
            command_text = str(commands)
        if isinstance(outputs, list):
            output_text = ", ".join(str(output) for output in outputs)
        else:
            output_text = str(outputs)
        items.append(
            f"<li><strong>{escape(name)}</strong><br>"
            f"<span class=\"muted\">commands: {escape(command_text or 'none')}<br>"
            f"outputs: {escape(output_text or 'network')}</span></li>"
        )
    return '<ul class="experiment-list">' + "".join(items) + "</ul>"


def render_create(query: dict[str, str], message: str = "", error: str = "") -> bytes:
    state = default_create_state(query, message or query.get("message", ""), error or query.get("error", ""))
    parameter_names = sorted(set(state.parameters) | set(state.selected_parameters), key=parameter_sort_key)
    experiments_json = json.dumps(state.experiments, indent=2)
    selected_count, space_size = selected_space_size(state.selected_parameters)

    grouped_rows: dict[str, list[str]] = {}
    for index, name in enumerate(parameter_names):
        value = state.parameters.get(name, "")
        selected_values = state.selected_parameters.get(name)
        checked = " checked" if selected_values is not None else ""
        values_text = stringify_values(selected_values) if selected_values is not None else default_value_text(value)
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
        category_html.append(
            f"""
<div class="category">
  <div class="category-header">
    <h3>{escape(category)}</h3>
    <span class="muted">{len(rows)} parameters</span>
  </div>
  <table>
    <thead><tr><th>Use</th><th>Parameter</th><th>Base value</th><th>DSE values</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table>
</div>
"""
        )
    for category, rows in sorted(grouped_rows.items()):
        category_html.append(
            f"""
<div class="category">
  <div class="category-header">
    <h3>{escape(category)}</h3>
    <span class="muted">{len(rows)} parameters</span>
  </div>
  <table>
    <thead><tr><th>Use</th><th>Parameter</th><th>Base value</th><th>DSE values</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table>
</div>
"""
        )

    body = f"""
<form id="create-save-form" method="post" action="/create/save">
  <section class="panel">
    <h2>DSE JSON files</h2>
    <div class="row3">
      {path_control("Properties file", "properties_path", state.properties_path, "properties")}
      {path_control("Load DSE JSON", "dse_json_path", state.dse_json_path, "json")}
      {path_control("Save DSE JSON", "save_path", state.save_path, "save_json")}
    </div>
    <div class="actions">
      <button type="submit" class="secondary" formaction="/create/refresh">Reload properties</button>
      <button type="submit" class="secondary" formaction="/create/load">Load JSON</button>
      <button type="submit">Save JSON</button>
    </div>
  </section>

  <section class="panel">
    <h2>Experiments section of the DSE JSON</h2>
    <div class="experiment-grid">
      <label>Experiments JSON array
        <textarea id="experiments_json" name="experiments_json">{escape(experiments_json)}</textarea>
      </label>
      <div>
        <h2>Configured experiments</h2>
        <div id="experiments-summary">{experiment_summary_html(state.experiments)}</div>
      </div>
    </div>
  </section>

  <section class="panel">
    <h2>Parameter space section of the DSE JSON</h2>
    <div class="space-summary">
      <span><strong id="parameter-space-size">{space_size}</strong> configurations</span>
      <span><strong id="selected-parameter-count">{selected_count}</strong> selected parameters</span>
      <span class="muted">Values start from the loaded base properties file.</span>
    </div>
    <input type="hidden" name="parameter_count" value="{len(parameter_names)}">
    {"".join(category_html)}
  </section>
</form>
"""
    return page("Create DSE JSON", body, state.message, state.error)

def render_run(query: dict[str, str], message: str = "", output: str = "", return_code: int | None = None) -> bytes:
    body = f"""
<form method="post" action="/run/start" class="panel">
  <div class="row">
    {path_control("Base properties", "base_properties", query.get("base_properties", DEFAULT_PROPERTIES), "properties")}
    {path_control("DSE JSON", "dse_json", query.get("dse_json", DEFAULT_DSE_JSON), "json")}
    {path_control("Output directory", "output_dir", query.get("output_dir", DEFAULT_RUN_DIR), "dir")}
    <label>Limit
      <input name="limit" value="{escape(query.get("limit", ""))}">
    </label>
  </div>
  <label><span><input type="checkbox" name="force" checked> Replace output directory if it already exists</span></label>
  <button type="submit">Run DSE</button>
</form>
"""
    if output:
        status = f"Process exit code: {return_code}"
        body += f"<section class=\"panel\"><h2>{escape(status)}</h2><pre>{escape(output)}</pre></section>"
    return page("Run DSE", body, message)


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
        }
    )

    body = f"""
<form method="get" action="/visualize" class="panel">
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
  <div class="panel figure">{figure}</div>
  <div class="panel">
    <h2>Parameter context</h2>
    {context}
    {warnings}
  </div>
</section>
"""
    return page("Open DSE Visualizer", body, error=current_error)


def build_visualizer_spec(dataset: visualizer.DseDataset, query: dict[str, str], report: str) -> visualizer.ChartSpec:
    metrics = {metric.key for metric in visualizer.available_metrics(report)}
    metric = query.get("metric") or visualizer.default_metric(report)
    if metric not in metrics:
        metric = visualizer.default_metric(report)
    graph = query.get("graph", "bar")
    if graph not in visualizer.GRAPH_TYPES:
        graph = "bar"
    aggregation = query.get("aggregation", "mean")
    if aggregation not in visualizer.AGGREGATIONS:
        aggregation = "mean"
    return visualizer.ChartSpec(
        report_type=report,
        metric_key=metric,
        x_param=query.get("x_param") or visualizer.default_x_parameter(dataset),
        graph_type=graph,
        aggregation=aggregation,
        experiment=query.get("experiment", "All"),
        filters=visualizer.parse_filters(None, query.get("filter", "")),
        title=query.get("title", ""),
        font_size=visualizer.parse_int(query.get("font_size"), 10),
    )


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
            self.respond_json(native_select_path(query))
        elif parsed.path == "/":
            self.respond(200, [], render_home())
        elif parsed.path == "/create":
            self.respond(200, [], render_create(query))
        elif parsed.path == "/run":
            self.respond(200, [], render_run(query))
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
                save_path, _payload = save_dse_from_form(form)
                params = {
                    "properties_path": form_value(form, "properties_path", DEFAULT_PROPERTIES),
                    "dse_json_path": display_path(save_path),
                    "save_path": display_path(save_path),
                    "load_json": "1",
                    "message": f"Saved {display_path(save_path)}",
                }
                self.respond(*redirect("/create?" + urlencode(params)))
            except Exception as exc:
                query = {
                    "properties_path": form_value(form, "properties_path", DEFAULT_PROPERTIES),
                    "dse_json_path": form_value(form, "dse_json_path", DEFAULT_DSE_JSON),
                    "save_path": form_value(form, "save_path", DEFAULT_SAVE_JSON),
                }
                self.respond(200, [], render_create(query, error=str(exc)))
        elif self.path == "/create/load":
            params = create_redirect_params(form, load_json=True)
            self.respond(*redirect("/create?" + urlencode(params)))
        elif self.path == "/create/refresh":
            params = create_redirect_params(form, load_json=False)
            self.respond(*redirect("/create?" + urlencode(params)))
        elif self.path == "/run/start":
            try:
                return_code, output = run_dse_from_form(form)
                query = {
                    "base_properties": form_value(form, "base_properties", DEFAULT_PROPERTIES),
                    "dse_json": form_value(form, "dse_json", DEFAULT_DSE_JSON),
                    "output_dir": form_value(form, "output_dir", DEFAULT_RUN_DIR),
                    "limit": form_value(form, "limit", ""),
                }
                message = "DSE run completed." if return_code == 0 else "DSE run failed."
                self.respond(200, [], render_run(query, message, output, return_code))
            except Exception as exc:
                self.respond(200, [], render_run({}, error_page_text(exc), "", None))
        else:
            self.respond(404, [], page("Not Found", "<p>Unknown wizard action.</p>"))

    def respond_pdf(self, query: dict[str, str]) -> None:
        try:
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


def error_page_text(exc: Exception) -> str:
    return str(exc)


def create_redirect_params(form: dict[str, list[str]], *, load_json: bool) -> dict[str, str]:
    params = {
        "properties_path": form_value(form, "properties_path", DEFAULT_PROPERTIES),
        "dse_json_path": form_value(form, "dse_json_path", DEFAULT_DSE_JSON),
        "save_path": form_value(form, "save_path", DEFAULT_SAVE_JSON),
    }
    if load_json:
        params["load_json"] = "1"
    return params


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Open the UltraViolet DSE browser wizard.")
    parser.add_argument("--host", default="127.0.0.1", help="Host for the local wizard server.")
    parser.add_argument("--port", type=int, default=0, help="Port for the local wizard server. Defaults to an available port.")
    parser.add_argument("--no-browser", action="store_true", help="Print the URL without opening a browser.")
    return parser


def main() -> int:
    args = build_arg_parser().parse_args()
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
