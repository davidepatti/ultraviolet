from __future__ import annotations

import json
from pathlib import Path
from typing import Any


SUPPORTED_COMMANDS = {"boot", "bal", "rndbal", "path", "inv", "route"}
SUPPORTED_OUTPUTS = {"network", "stat", "stats", "invoice", "invoices", "invoice_report"}


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: line {exc.lineno}, column {exc.colno}: {exc.msg}") from exc
    if not isinstance(payload, dict):
        raise ValueError("DSE JSON must be an object with 'parameters' and 'experiments' sections.")
    return payload


def load_dse_payload(path: Path) -> dict[str, Any]:
    payload = load_json_object(path)
    if "parameters" not in payload:
        raise ValueError("DSE JSON for uv_dse_run must contain a 'parameters' object.")
    return payload


def load_parameter_space(parameter_space_path: Path) -> tuple[list[str], dict[str, list[str]]]:
    payload = load_json_object(parameter_space_path)
    raw_parameters = payload["parameters"] if "parameters" in payload else payload
    parameters = validate_parameters(raw_parameters)
    ordered_names = list(parameters)
    normalized_parameters = {
        name: [stringify_property_value(name, value) for value in values]
        for name, values in parameters.items()
    }
    return ordered_names, normalized_parameters


def validate_parameters(raw_parameters: object) -> dict[str, list[object]]:
    if not isinstance(raw_parameters, dict) or not raw_parameters:
        raise ValueError(
            "Parameter space must be a non-empty object, or an object with a non-empty 'parameters' object."
        )

    parameters: dict[str, list[object]] = {}
    for raw_name, raw_values in raw_parameters.items():
        if not isinstance(raw_name, str) or not raw_name.strip():
            raise ValueError("Every parameter name must be a non-empty string.")
        if not isinstance(raw_values, list) or not raw_values:
            raise ValueError(f"Parameter '{raw_name}' must provide a non-empty JSON array of values.")
        for value in raw_values:
            validate_parameter_value(raw_name, value)
        parameters[raw_name] = list(raw_values)
    return parameters


def validate_parameter_value(parameter_name: str, value: object) -> None:
    if value is None:
        raise ValueError(f"Parameter '{parameter_name}' cannot contain null values.")
    if isinstance(value, (str, int, float, bool)):
        return
    raise ValueError(
        f"Parameter '{parameter_name}' contains an unsupported value type: {type(value).__name__}. "
        "Only strings, numbers, and booleans are supported."
    )


def stringify_property_value(parameter_name: str, value: object) -> str:
    validate_parameter_value(parameter_name, value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value, ensure_ascii=True, separators=(",", ":"))
    return str(value)


def validate_experiments(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw_experiments = payload.get("experiments")
    if not isinstance(raw_experiments, list) or not raw_experiments:
        raise ValueError("DSE JSON must contain a non-empty 'experiments' array.")

    experiments: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    for index, raw_experiment in enumerate(raw_experiments, start=1):
        if not isinstance(raw_experiment, dict):
            raise ValueError(f"Experiment #{index} must be a JSON object.")

        experiment = dict(raw_experiment)
        name = str(experiment.get("name") or f"experiment_{index}").strip()
        if not name:
            raise ValueError(f"Experiment #{index} has an empty name.")
        if name in seen_names:
            raise ValueError(f"Duplicate experiment name: {name}")
        seen_names.add(name)

        commands = experiment.get("commands")
        if not isinstance(commands, list) or not commands:
            raise ValueError(f"Experiment '{name}' must contain a non-empty 'commands' array.")
        for command_index, command in enumerate(commands, start=1):
            command_name = normalize_command_name(command)
            if command_name not in SUPPORTED_COMMANDS:
                raise ValueError(
                    f"Experiment '{name}' command #{command_index} uses unsupported command '{command_name}'. "
                    f"Supported commands: {', '.join(sorted(SUPPORTED_COMMANDS))}."
                )

        outputs = experiment.get("outputs", experiment.get("reports", ["network"]))
        if isinstance(outputs, str):
            outputs = [outputs]
        if not isinstance(outputs, list) or not outputs:
            raise ValueError(f"Experiment '{name}' outputs must be a non-empty array or string when provided.")
        normalized_outputs = []
        for output in outputs:
            output_name = str(output).strip().lower()
            if output_name not in SUPPORTED_OUTPUTS:
                raise ValueError(
                    f"Experiment '{name}' requests unsupported output '{output_name}'. "
                    f"Supported outputs: {', '.join(sorted(SUPPORTED_OUTPUTS))}."
                )
            normalized_outputs.append(normalize_output_name(output_name))

        experiment["name"] = name
        experiment["outputs"] = normalized_outputs
        experiments.append(experiment)

    return experiments


def normalize_output_name(output_name: str) -> str:
    if output_name in {"network", "stat", "stats"}:
        return "network"
    if output_name in {"invoice", "invoices", "invoice_report"}:
        return "invoice"
    return output_name


def normalize_command_name(command: object) -> str:
    if isinstance(command, str):
        command_name = command.strip()
    elif isinstance(command, dict):
        raw_name = command.get("command", command.get("cmd"))
        if not isinstance(raw_name, str) or not raw_name.strip():
            raise ValueError("Experiment command objects must define a non-empty 'command' or 'cmd'.")
        command_name = raw_name.strip()
    else:
        raise ValueError("Experiment commands must be strings or objects.")
    command_name = command_name.lower()
    if not command_name:
        raise ValueError("Experiment commands cannot be empty.")
    return command_name


def validate_dse_payload(payload: dict[str, Any]) -> dict[str, Any]:
    parameters = validate_parameters(payload.get("parameters"))
    experiments = validate_experiments(payload)
    return {"parameters": parameters, "experiments": experiments}


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
        for logical_line in logical_property_lines(path):
            stripped = logical_line.lstrip()
            if not stripped or stripped.startswith("#") or stripped.startswith("!"):
                continue
            directive = parse_include_directive(stripped)
            if directive:
                for include_text in directive:
                    include_path = (path.parent / include_text).resolve()
                    load_properties_into(include_path, merged, stack)
                continue

            key, value = parse_property_line(logical_line)
            if key:
                local[key] = value
    finally:
        stack.pop()
    merged.update(local)


def logical_property_lines(path: Path) -> list[str]:
    lines: list[str] = []
    current = ""
    continuing = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.lstrip() if continuing else raw_line
        if continues_property_line(line):
            current += line[:-1]
            continuing = True
        else:
            lines.append(current + line)
            current = ""
            continuing = False
    if current:
        lines.append(current)
    return lines


def continues_property_line(line: str) -> bool:
    backslashes = 0
    for char in reversed(line):
        if char == "\\":
            backslashes += 1
        else:
            break
    return backslashes % 2 == 1


def parse_include_directive(line: str) -> list[str] | None:
    for directive in ("@include", "@import"):
        if line.startswith(directive):
            remainder = line[len(directive):].strip()
            if remainder.startswith("=") or remainder.startswith(":"):
                remainder = remainder[1:].strip()
            if not remainder:
                raise ValueError(f"Missing path in {directive} directive")
            return [unescape_property(part.strip()) for part in remainder.split(",") if part.strip()]
    return None


def parse_property_line(line: str) -> tuple[str, str]:
    key_end, separator_index = find_property_separator(line)
    key = unescape_property(line[:key_end].strip())
    if separator_index is None:
        return key, ""

    value_start = separator_index
    if value_start < len(line) and line[value_start] in "=:":
        value_start += 1
    while value_start < len(line) and line[value_start].isspace():
        value_start += 1
    return key, unescape_property(line[value_start:])


def find_property_separator(line: str) -> tuple[int, int | None]:
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
            continue
        if char in "=:" or char.isspace():
            key_end = index
            separator_index = index
            while separator_index < len(line) and line[separator_index].isspace():
                separator_index += 1
            return key_end, separator_index
    return len(line), None


def unescape_property(value: str) -> str:
    result: list[str] = []
    index = 0
    while index < len(value):
        char = value[index]
        if char != "\\" or index == len(value) - 1:
            result.append(char)
            index += 1
            continue

        index += 1
        escaped = value[index]
        if escaped == "t":
            result.append("\t")
        elif escaped == "n":
            result.append("\n")
        elif escaped == "r":
            result.append("\r")
        elif escaped == "f":
            result.append("\f")
        elif escaped == "u" and index + 4 < len(value):
            hex_value = value[index + 1:index + 5]
            try:
                result.append(chr(int(hex_value, 16)))
                index += 4
            except ValueError:
                result.append("u")
        else:
            result.append(escaped)
        index += 1
    return "".join(result)
