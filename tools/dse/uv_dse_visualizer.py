#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


sys.dont_write_bytecode = True

NETWORK_ROWS = [
    "Graph Nodes",
    "Graph Channels",
    "Node Channels",
    "Node Capacity",
    "LN balance",
    "Invoices",
    "Outbound %",
]

NETWORK_STATS = [
    ("min", "Min"),
    ("max", "Max"),
    ("average", "Average"),
    ("std_deviation", "Std Deviation"),
    ("q1", "1st quartile"),
    ("median", "median"),
    ("q3", "3rd quartile"),
]

AGGREGATIONS = ["mean", "median", "sum", "min", "max", "count"]
GRAPH_TYPES = ["bar", "line", "scatter", "pie"]
PALETTE = [
    (0.10, 0.31, 0.57),
    (0.75, 0.29, 0.13),
    (0.12, 0.48, 0.34),
    (0.83, 0.60, 0.12),
    (0.56, 0.20, 0.20),
    (0.18, 0.55, 0.60),
    (0.32, 0.32, 0.32),
    (0.49, 0.43, 0.18),
]


@dataclass(frozen=True)
class RunRecord:
    run_dir: Path
    experiment: str
    status: str
    return_code: int
    overrides: dict[str, str]
    network_report: Path | None
    invoice_report: Path | None


@dataclass(frozen=True)
class Metric:
    key: str
    label: str
    report_type: str


@dataclass(frozen=True)
class ChartSpec:
    report_type: str
    metric_key: str
    x_param: str
    graph_type: str
    aggregation: str
    experiment: str
    filters: dict[str, str]
    title: str
    font_size: int


@dataclass(frozen=True)
class ChartData:
    title: str
    subtitle: str
    metric_label: str
    x_label: str
    labels: list[str]
    values: list[float]
    counts: list[int]
    parameter_summary: list[str]
    warnings: list[str]


class DseDataset:
    def __init__(self, root: Path, runs: list[RunRecord]) -> None:
        self.root = root
        self.runs = runs
        self._network_cache: dict[Path, dict[tuple[str, str], float]] = {}
        self._invoice_cache: dict[Path, list[dict[str, str]]] = {}

    @property
    def experiments(self) -> list[str]:
        return sorted({run.experiment for run in self.runs})

    @property
    def parameter_names(self) -> list[str]:
        names = sorted({name for run in self.runs for name in run.overrides})
        return ["experiment"] + names

    def values_for(self, parameter: str, runs: list[RunRecord] | None = None) -> list[str]:
        source = runs if runs is not None else self.runs
        if parameter == "experiment":
            return sorted({run.experiment for run in source})
        return sorted({run.overrides.get(parameter, "<missing>") for run in source}, key=natural_sort_key)

    def select_runs(self, spec: ChartSpec) -> list[RunRecord]:
        selected: list[RunRecord] = []
        for run in self.runs:
            if run.status != "success" or run.return_code != 0:
                continue
            if spec.experiment != "All" and run.experiment != spec.experiment:
                continue
            if spec.report_type == "network" and run.network_report is None:
                continue
            if spec.report_type == "invoice" and run.invoice_report is None:
                continue

            rejected = False
            for key, expected in spec.filters.items():
                actual = run.experiment if key == "experiment" else run.overrides.get(key)
                if actual != expected:
                    rejected = True
                    break
            if not rejected:
                selected.append(run)
        return selected

    def metric_value(self, run: RunRecord, metric_key: str) -> float | None:
        if metric_key.startswith("network:"):
            if run.network_report is None:
                return None
            _, row_name, stat_label = metric_key.split(":", 2)
            data = self._network_cache.setdefault(run.network_report, parse_network_report(run.network_report))
            return data.get((row_name, stat_label))

        if metric_key.startswith("invoice:"):
            if run.invoice_report is None:
                return None
            rows = self._invoice_cache.setdefault(run.invoice_report, parse_invoice_report(run.invoice_report))
            return invoice_metric_value(rows, metric_key.removeprefix("invoice:"))

        raise ValueError(f"Unsupported metric key: {metric_key}")


def load_dataset(root: Path) -> DseDataset:
    root = root.expanduser().resolve()
    jsonl_path = root / "runs_index.jsonl"
    csv_path = root / "runs_index.csv"
    config_overrides = load_config_overrides(root)

    if jsonl_path.is_file():
        raw_runs = [json.loads(line) for line in jsonl_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    elif csv_path.is_file():
        with csv_path.open(newline="", encoding="utf-8") as csv_file:
            raw_runs = list(csv.DictReader(csv_file))
    else:
        raise ValueError(
            f"No runs_index.jsonl or runs_index.csv found in {root}. "
            "Select an output directory produced by uv_dse_run, or create one from tools/dse with: "
            "./uv_dse_run ../../uv_configs/template.properties quickstart_dse.json "
            "--output-dir dse_runs/quickstart --force"
        )

    runs: list[RunRecord] = []
    for raw in raw_runs:
        config_file = str(raw.get("config_file", ""))
        overrides = normalize_overrides(raw.get("overrides")) or config_overrides.get(config_file, {})
        run_dir = resolve_relative(root, raw.get("run_dir"))
        network_report = resolve_optional_relative(root, raw.get("network_report"))
        invoice_report = resolve_optional_relative(root, raw.get("invoice_report"))
        runs.append(
            RunRecord(
                run_dir=run_dir,
                experiment=str(raw.get("experiment", "")),
                status=str(raw.get("status", "")),
                return_code=parse_int(raw.get("return_code"), default=1),
                overrides=overrides,
                network_report=network_report if network_report and network_report.is_file() else None,
                invoice_report=invoice_report if invoice_report and invoice_report.is_file() else None,
            )
        )

    if not runs:
        raise ValueError(f"No DSE runs found in {root}")
    return DseDataset(root, runs)


def load_config_overrides(root: Path) -> dict[str, dict[str, str]]:
    manifest_path = root / "configs" / "manifest.json"
    if not manifest_path.is_file():
        return {}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}

    mapping: dict[str, dict[str, str]] = {}
    for generated in manifest.get("generated_files", []):
        if not isinstance(generated, dict):
            continue
        file_name = str(generated.get("file", ""))
        overrides = normalize_overrides(generated.get("overrides"))
        if file_name and overrides:
            mapping[file_name] = overrides
    return mapping


def normalize_overrides(value: object) -> dict[str, str]:
    if isinstance(value, dict):
        return {str(key): str(raw_value) for key, raw_value in value.items()}
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return normalize_overrides(parsed)
    return {}


def resolve_relative(root: Path, raw_path: object) -> Path:
    value = str(raw_path or "")
    path = Path(value)
    return path if path.is_absolute() else root / path


def resolve_optional_relative(root: Path, raw_path: object) -> Path | None:
    if raw_path is None or str(raw_path).strip() == "":
        return None
    return resolve_relative(root, raw_path)


def parse_int(value: object, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def parse_float(value: object) -> float | None:
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return None


def parse_bool(value: object) -> bool:
    return str(value).strip().lower() in {"true", "1", "yes", "y"}


def parse_network_report(path: Path) -> dict[tuple[str, str], float]:
    parsed: dict[tuple[str, str], float] = {}
    rows_by_length = sorted(NETWORK_ROWS, key=len, reverse=True)
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        for row_name in rows_by_length:
            if not line.startswith(row_name):
                continue
            values = [parse_float(token) for token in line[len(row_name):].strip().split()]
            numeric_values = [value for value in values if value is not None]
            if len(numeric_values) >= len(NETWORK_STATS):
                for (_, stat_label), value in zip(NETWORK_STATS, numeric_values):
                    parsed[(row_name, stat_label)] = value
            break
    return parsed


def parse_invoice_report(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as csv_file:
        return list(csv.DictReader(csv_file))


def invoice_metric_value(rows: list[dict[str, str]], metric: str) -> float | None:
    if metric == "invoice_count":
        return float(len(rows))
    if not rows:
        return None

    if metric == "success_rate":
        return 100.0 * sum(1 for row in rows if parse_bool(row.get("success"))) / len(rows)
    if metric == "failure_rate":
        return 100.0 * sum(1 for row in rows if not parse_bool(row.get("success"))) / len(rows)

    if metric.startswith("avg_"):
        field = metric.removeprefix("avg_")
        values = invoice_numeric_values(rows, field)
        return statistics.mean(values) if values else None

    if metric.startswith("sum_"):
        field = metric.removeprefix("sum_")
        values = invoice_numeric_values(rows, field)
        return float(sum(values)) if values else 0.0

    return None


def invoice_numeric_values(rows: list[dict[str, str]], field: str) -> list[float]:
    values: list[float] = []
    for row in rows:
        value = parse_float(row.get(field))
        if value is not None:
            values.append(value)
    return values


def available_metrics(report_type: str) -> list[Metric]:
    if report_type == "network":
        metrics: list[Metric] = []
        for row_name in NETWORK_ROWS:
            for _, stat_label in NETWORK_STATS:
                metrics.append(
                    Metric(
                        key=f"network:{row_name}:{stat_label}",
                        label=f"{row_name} / {stat_label}",
                        report_type="network",
                    )
                )
        return metrics

    invoice_metrics = [
        ("invoice_count", "Invoice count"),
        ("success_rate", "Success rate (%)"),
        ("failure_rate", "Failure rate (%)"),
        ("avg_amt", "Average amount"),
        ("avg_max_fees", "Average max fees"),
        ("avg_search_returned_paths", "Average returned paths"),
        ("avg_search_investigated_states", "Average investigated states"),
        ("avg_search_expanded_edges", "Average expanded edges"),
        ("avg_candidate_paths", "Average candidate paths"),
        ("avg_attempted_paths", "Average attempted paths"),
        ("sum_filtered_policy", "Filtered by policy"),
        ("sum_filtered_capacity", "Filtered by capacity"),
        ("sum_filtered_local_liquidity", "Filtered by local liquidity"),
        ("sum_filtered_max_fees", "Filtered by max fees"),
        ("sum_attempt_failed_temporary_channel", "Temporary channel failures"),
        ("sum_attempt_failed_expiry_too_soon", "Expiry-too-soon failures"),
        ("sum_attempt_failed_local_liquidity", "Local liquidity failures"),
        ("sum_attempt_failed_timeout", "Timeout failures"),
        ("sum_attempt_failed_unknown", "Unknown failures"),
    ]
    return [Metric(key=f"invoice:{key}", label=label, report_type="invoice") for key, label in invoice_metrics]


def metric_by_key(report_type: str) -> dict[str, Metric]:
    return {metric.key: metric for metric in available_metrics(report_type)}


def default_metric(report_type: str) -> str:
    if report_type == "network":
        return "network:Node Channels:Average"
    return "invoice:success_rate"


def default_x_parameter(dataset: DseDataset) -> str:
    for name in dataset.parameter_names:
        if name != "experiment" and len(dataset.values_for(name)) > 1:
            return name
    return dataset.parameter_names[0]


def prepare_chart_data(dataset: DseDataset, spec: ChartSpec) -> ChartData:
    if spec.aggregation not in AGGREGATIONS:
        raise ValueError(f"Unsupported aggregation: {spec.aggregation}")
    if spec.graph_type not in GRAPH_TYPES:
        raise ValueError(f"Unsupported graph type: {spec.graph_type}")

    selected = dataset.select_runs(spec)
    if not selected:
        raise ValueError("No successful runs match the selected report, experiment, and filters.")

    groups: dict[str, list[float]] = {}
    for run in selected:
        value = dataset.metric_value(run, spec.metric_key)
        if value is None or math.isnan(value):
            continue
        x_value = run.experiment if spec.x_param == "experiment" else run.overrides.get(spec.x_param, "<missing>")
        groups.setdefault(str(x_value), []).append(value)

    if not groups:
        raise ValueError("The selected metric has no numeric values in the matching runs.")

    labels = sorted(groups, key=natural_sort_key)
    values = [aggregate(groups[label], spec.aggregation) for label in labels]
    counts = [len(groups[label]) for label in labels]
    metric = metric_by_key(spec.report_type).get(spec.metric_key)
    metric_label = metric.label if metric else spec.metric_key

    parameter_summary, warnings = build_parameter_summary(dataset, spec, selected)
    title = spec.title.strip() or f"{metric_label} by {spec.x_param}"
    subtitle = (
        f"Report: {spec.report_type}; aggregation: {spec.aggregation}; "
        f"runs used: {sum(counts)}; generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}"
    )
    return ChartData(
        title=title,
        subtitle=subtitle,
        metric_label=metric_label,
        x_label=spec.x_param,
        labels=labels,
        values=values,
        counts=counts,
        parameter_summary=parameter_summary,
        warnings=warnings,
    )


def aggregate(values: list[float], method: str) -> float:
    if method == "mean":
        return statistics.mean(values)
    if method == "median":
        return statistics.median(values)
    if method == "sum":
        return float(sum(values))
    if method == "min":
        return min(values)
    if method == "max":
        return max(values)
    if method == "count":
        return float(len(values))
    raise ValueError(f"Unsupported aggregation: {method}")


def build_parameter_summary(dataset: DseDataset, spec: ChartSpec, selected: list[RunRecord]) -> tuple[list[str], list[str]]:
    lines: list[str] = []
    warnings: list[str] = []
    for parameter in dataset.parameter_names:
        values = dataset.values_for(parameter, selected)
        value_text = compact_values(values)
        if parameter == spec.x_param:
            lines.append(f"Varied on x-axis: {parameter} = {value_text}")
        elif parameter in spec.filters:
            lines.append(f"Fixed by filter: {parameter} = {spec.filters[parameter]}")
        elif parameter == "experiment" and spec.experiment != "All":
            lines.append(f"Fixed by experiment selector: experiment = {spec.experiment}")
        elif len(values) == 1:
            lines.append(f"Fixed background: {parameter} = {values[0]}")
        else:
            line = f"Aggregated background variation: {parameter} = {value_text}"
            lines.append(line)
            warnings.append(line)
    return lines, warnings


def compact_values(values: list[str], max_items: int = 6) -> str:
    if len(values) <= max_items:
        return ", ".join(values)
    return ", ".join(values[:max_items]) + f", ... ({len(values)} values)"


def natural_sort_key(value: object) -> tuple[int, float | str]:
    text = str(value)
    number = parse_float(text)
    if number is not None:
        return (0, number)
    return (1, text)


def parse_filters(values: list[str] | None, text: str = "") -> dict[str, str]:
    filters: dict[str, str] = {}
    raw_parts: list[str] = []
    if text.strip():
        raw_parts.extend(part.strip() for part in text.split(",") if part.strip())
    if values:
        raw_parts.extend(values)

    for raw in raw_parts:
        if "=" not in raw:
            raise ValueError(f"Filter must use key=value syntax: {raw}")
        key, value = raw.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or not value:
            raise ValueError(f"Filter must use key=value syntax: {raw}")
        filters[key] = value
    return filters


def report_type_from_params(params: dict[str, str]) -> str:
    report = params.get("report", "network")
    return report if report in {"network", "invoice"} else "network"


def chart_spec_from_params(
    dataset: DseDataset,
    params: dict[str, str],
    *,
    report_type: str | None = None,
) -> ChartSpec:
    report = report_type or report_type_from_params(params)
    metric_keys = {metric.key for metric in available_metrics(report)}
    metric = params.get("metric") or default_metric(report)
    if metric not in metric_keys:
        metric = default_metric(report)

    graph = params.get("graph", "bar")
    if graph not in GRAPH_TYPES:
        graph = "bar"

    aggregation = params.get("aggregation", "mean")
    if aggregation not in AGGREGATIONS:
        aggregation = "mean"

    return ChartSpec(
        report_type=report,
        metric_key=metric,
        x_param=params.get("x_param") or default_x_parameter(dataset),
        graph_type=graph,
        aggregation=aggregation,
        experiment=params.get("experiment", "All"),
        filters=parse_filters(None, params.get("filter", "")),
        title=params.get("title", ""),
        font_size=parse_int(params.get("font_size"), 10),
    )


class PdfCanvas:
    def __init__(self, width: float = 792.0, height: float = 612.0) -> None:
        self.width = width
        self.height = height
        self.commands: list[str] = []

    def add(self, command: str) -> None:
        self.commands.append(command)

    def stroke_color(self, color: tuple[float, float, float]) -> None:
        self.add(f"{color[0]:.3f} {color[1]:.3f} {color[2]:.3f} RG")

    def fill_color(self, color: tuple[float, float, float]) -> None:
        self.add(f"{color[0]:.3f} {color[1]:.3f} {color[2]:.3f} rg")

    def line_width(self, width: float) -> None:
        self.add(f"{width:.3f} w")

    def line(self, x1: float, y1: float, x2: float, y2: float) -> None:
        self.add(f"{x1:.2f} {y1:.2f} m {x2:.2f} {y2:.2f} l S")

    def rect(self, x: float, y: float, width: float, height: float, *, stroke: bool, fill: bool) -> None:
        op = "B" if stroke and fill else "S" if stroke else "f"
        self.add(f"{x:.2f} {y:.2f} {width:.2f} {height:.2f} re {op}")

    def polygon(self, points: list[tuple[float, float]], *, stroke: bool, fill: bool) -> None:
        if len(points) < 3:
            return
        parts = [f"{points[0][0]:.2f} {points[0][1]:.2f} m"]
        for x, y in points[1:]:
            parts.append(f"{x:.2f} {y:.2f} l")
        parts.append("h")
        parts.append("B" if stroke and fill else "S" if stroke else "f")
        self.add(" ".join(parts))

    def circle(self, x: float, y: float, radius: float, *, stroke: bool, fill: bool) -> None:
        points = []
        for index in range(24):
            angle = 2.0 * math.pi * index / 24
            points.append((x + radius * math.cos(angle), y + radius * math.sin(angle)))
        self.polygon(points, stroke=stroke, fill=fill)

    def text(
        self,
        x: float,
        y: float,
        value: str,
        *,
        size: float,
        bold: bool = False,
        align: str = "left",
    ) -> None:
        font = "F2" if bold else "F1"
        escaped = escape_pdf_text(value)
        width = approximate_text_width(value, size)
        x_pos = x
        if align == "center":
            x_pos -= width / 2.0
        elif align == "right":
            x_pos -= width
        self.add(f"BT /{font} {size:.2f} Tf {x_pos:.2f} {y:.2f} Td ({escaped}) Tj ET")

    def save(self, path: Path) -> None:
        content = ("\n".join(self.commands) + "\n").encode("latin-1", errors="replace")
        objects = [
            b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            (
                f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {self.width:.2f} {self.height:.2f}] "
                f"/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>"
            ).encode("ascii"),
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
            b"<< /Length " + str(len(content)).encode("ascii") + b" >>\nstream\n" + content + b"endstream",
        ]

        output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for index, obj in enumerate(objects, start=1):
            offsets.append(len(output))
            output.extend(f"{index} 0 obj\n".encode("ascii"))
            output.extend(obj)
            output.extend(b"\nendobj\n")

        xref_start = len(output)
        output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
        output.extend(b"0000000000 65535 f \n")
        for offset in offsets[1:]:
            output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
        output.extend(
            (
                f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
                f"startxref\n{xref_start}\n%%EOF\n"
            ).encode("ascii")
        )
        path.write_bytes(output)


def escape_pdf_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def approximate_text_width(value: str, size: float) -> float:
    return len(value) * size * 0.52


def rgb_to_hex(color: tuple[float, float, float]) -> str:
    return "#" + "".join(f"{max(0, min(255, round(component * 255))):02x}" for component in color)


def nice_number(value: float) -> str:
    if abs(value) >= 1000:
        return f"{value:,.0f}"
    if abs(value) >= 10:
        return f"{value:.1f}"
    return f"{value:.2f}"


def padded_y_range(values: list[float]) -> tuple[float, float]:
    if not values:
        return 0.0, 1.0
    y_min = min(0.0, min(values))
    y_max = max(values)
    if y_max == y_min:
        y_max = y_min + 1.0
    padding = (y_max - y_min) * 0.08
    return y_min, y_max + padding


def wrap_text(text: str, max_chars: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if len(candidate) <= max_chars:
            current = candidate
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def export_pdf(path: Path, data: ChartData, graph_type: str, font_size: int = 10) -> None:
    canvas = PdfCanvas()
    draw_pdf_page(canvas, data, graph_type, font_size)
    path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path)


def draw_pdf_page(canvas: PdfCanvas, data: ChartData, graph_type: str, font_size: int) -> None:
    canvas.fill_color((1.0, 1.0, 1.0))
    canvas.rect(0, 0, canvas.width, canvas.height, stroke=False, fill=True)
    canvas.fill_color((0.08, 0.09, 0.10))
    canvas.text(44, 570, data.title, size=font_size + 8, bold=True)
    canvas.fill_color((0.25, 0.27, 0.29))
    canvas.text(44, 548, data.subtitle, size=font_size)

    if graph_type == "pie":
        draw_pdf_pie(canvas, data, font_size)
    else:
        draw_pdf_xy(canvas, data, graph_type, font_size)

    y = 96
    canvas.fill_color((0.08, 0.09, 0.10))
    canvas.text(44, y, "Parameter context", size=font_size + 1, bold=True)
    y -= 16
    for line in data.parameter_summary[:6]:
        for wrapped in wrap_text(line, 105):
            canvas.fill_color((0.25, 0.27, 0.29))
            canvas.text(44, y, wrapped, size=max(7, font_size - 1))
            y -= 12
            if y < 18:
                break
        if y < 18:
            break


def draw_pdf_xy(canvas: PdfCanvas, data: ChartData, graph_type: str, font_size: int) -> None:
    left, bottom, width, height = 82.0, 150.0, 610.0, 350.0
    y_min, y_max = padded_y_range(data.values)
    canvas.stroke_color((0.72, 0.75, 0.78))
    canvas.fill_color((0.98, 0.98, 0.97))
    canvas.rect(left, bottom, width, height, stroke=True, fill=True)
    canvas.stroke_color((0.72, 0.75, 0.78))
    canvas.line_width(0.6)

    for tick in range(6):
        ratio = tick / 5
        y = bottom + ratio * height
        value = y_min + ratio * (y_max - y_min)
        canvas.line(left, y, left + width, y)
        canvas.fill_color((0.32, 0.34, 0.36))
        canvas.text(left - 10, y - 4, nice_number(value), size=max(7, font_size - 2), align="right")

    def map_y(value: float) -> float:
        return bottom + (value - y_min) / (y_max - y_min) * height

    n = len(data.labels)
    step = width / max(1, n)
    centers = [left + step * (index + 0.5) for index in range(n)]

    if graph_type == "bar":
        bar_width = min(46.0, step * 0.62)
        for index, (label, value) in enumerate(zip(data.labels, data.values)):
            color = PALETTE[index % len(PALETTE)]
            x = centers[index] - bar_width / 2.0
            y = map_y(value)
            canvas.fill_color(color)
            canvas.stroke_color(color)
            canvas.rect(x, bottom, bar_width, max(0.5, y - bottom), stroke=False, fill=True)
            canvas.fill_color((0.08, 0.09, 0.10))
            canvas.text(centers[index], y + 6, nice_number(value), size=max(7, font_size - 2), align="center")
    else:
        points = [(centers[index], map_y(value)) for index, value in enumerate(data.values)]
        if graph_type == "line" and len(points) > 1:
            canvas.stroke_color(PALETTE[0])
            canvas.line_width(2.0)
            parts = [f"{points[0][0]:.2f} {points[0][1]:.2f} m"]
            for x, y in points[1:]:
                parts.append(f"{x:.2f} {y:.2f} l")
            parts.append("S")
            canvas.add(" ".join(parts))
        for index, (x, y) in enumerate(points):
            color = PALETTE[index % len(PALETTE)] if graph_type == "scatter" else PALETTE[0]
            canvas.fill_color(color)
            canvas.stroke_color((1.0, 1.0, 1.0))
            canvas.circle(x, y, 4.5, stroke=True, fill=True)
            canvas.fill_color((0.08, 0.09, 0.10))
            canvas.text(x, y + 9, nice_number(data.values[index]), size=max(7, font_size - 2), align="center")

    canvas.stroke_color((0.08, 0.09, 0.10))
    canvas.line_width(1.0)
    canvas.line(left, bottom, left + width, bottom)
    canvas.line(left, bottom, left, bottom + height)

    for label, x in zip(data.labels, centers):
        canvas.fill_color((0.20, 0.22, 0.24))
        canvas.text(x, bottom - 18, label[:18], size=max(7, font_size - 2), align="center")
    canvas.fill_color((0.08, 0.09, 0.10))
    canvas.text(left + width / 2, 122, data.x_label, size=font_size, bold=True, align="center")
    canvas.text(44, bottom + height / 2, data.metric_label, size=font_size, bold=True)


def draw_pdf_pie(canvas: PdfCanvas, data: ChartData, font_size: int) -> None:
    total = sum(max(0.0, value) for value in data.values)
    if total <= 0:
        canvas.fill_color((0.72, 0.18, 0.18))
        canvas.text(120, 350, "Pie chart requires positive values.", size=font_size + 2, bold=True)
        return

    cx, cy, radius = 310.0, 330.0, 145.0
    start_angle = math.pi / 2
    for index, value in enumerate(data.values):
        fraction = max(0.0, value) / total
        end_angle = start_angle - 2.0 * math.pi * fraction
        points = [(cx, cy)]
        steps = max(4, int(40 * fraction))
        for step in range(steps + 1):
            angle = start_angle + (end_angle - start_angle) * step / steps
            points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
        canvas.fill_color(PALETTE[index % len(PALETTE)])
        canvas.stroke_color((1.0, 1.0, 1.0))
        canvas.polygon(points, stroke=True, fill=True)
        start_angle = end_angle

    legend_x, legend_y = 510.0, 438.0
    for index, (label, value) in enumerate(zip(data.labels, data.values)):
        y = legend_y - index * 24
        canvas.fill_color(PALETTE[index % len(PALETTE)])
        canvas.rect(legend_x, y - 4, 10, 10, stroke=False, fill=True)
        canvas.fill_color((0.08, 0.09, 0.10))
        percent = 100.0 * max(0.0, value) / total
        canvas.text(legend_x + 16, y - 2, f"{label}: {nice_number(value)} ({percent:.1f}%)", size=font_size)


class DseVisualizerGui:
    def __init__(self, root: object, initial_dir: Path | None = None) -> None:
        import tkinter as tk
        from tkinter import ttk

        self.tk = tk
        self.ttk = ttk
        self.root = root
        self.dataset: DseDataset | None = None
        self.current_data: ChartData | None = None

        root.title("UltraViolet DSE Visualizer")
        root.geometry("1100x760")

        self.input_var = tk.StringVar(value=str(initial_dir) if initial_dir else "")
        self.report_var = tk.StringVar(value="network")
        self.metric_var = tk.StringVar()
        self.x_param_var = tk.StringVar()
        self.graph_var = tk.StringVar(value="bar")
        self.aggregation_var = tk.StringVar(value="mean")
        self.experiment_var = tk.StringVar(value="All")
        self.filters_var = tk.StringVar()
        self.title_var = tk.StringVar()
        self.font_var = tk.IntVar(value=10)
        self.status_var = tk.StringVar(value="No DSE directory loaded.")

        main = ttk.Frame(root, padding=10)
        main.pack(fill=tk.BOTH, expand=True)

        path_row = ttk.Frame(main)
        path_row.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(path_row, text="DSE output").pack(side=tk.LEFT)
        ttk.Entry(path_row, textvariable=self.input_var).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=8)
        ttk.Button(path_row, text="Browse", command=self.browse).pack(side=tk.LEFT)
        ttk.Button(path_row, text="Load", command=self.load).pack(side=tk.LEFT, padx=(6, 0))

        controls = ttk.Frame(main)
        controls.pack(fill=tk.X, pady=(0, 8))
        self.report_combo = self.add_combo(controls, "Report", self.report_var, ["network", "invoice"], self.on_report_change)
        self.metric_combo = self.add_combo(controls, "Metric", self.metric_var, [], None, width=34)
        self.x_combo = self.add_combo(controls, "X parameter", self.x_param_var, [], None, width=22)
        self.experiment_combo = self.add_combo(controls, "Experiment", self.experiment_var, ["All"], None, width=22)
        self.graph_combo = self.add_combo(controls, "Graph", self.graph_var, GRAPH_TYPES, None, width=10)
        self.aggregation_combo = self.add_combo(controls, "Aggregation", self.aggregation_var, AGGREGATIONS, None, width=10)

        options = ttk.Frame(main)
        options.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(options, text="Fixed filters").pack(side=tk.LEFT)
        ttk.Entry(options, textvariable=self.filters_var, width=40).pack(side=tk.LEFT, padx=8)
        ttk.Label(options, text="Title").pack(side=tk.LEFT)
        ttk.Entry(options, textvariable=self.title_var, width=34).pack(side=tk.LEFT, padx=8)
        ttk.Label(options, text="Font size").pack(side=tk.LEFT)
        ttk.Spinbox(options, from_=8, to=18, textvariable=self.font_var, width=4).pack(side=tk.LEFT, padx=(4, 8))
        ttk.Button(options, text="Preview", command=self.preview).pack(side=tk.LEFT)
        ttk.Button(options, text="Export PDF", command=self.export).pack(side=tk.LEFT, padx=(6, 0))

        body = ttk.Frame(main)
        body.pack(fill=tk.BOTH, expand=True)
        self.canvas = tk.Canvas(body, bg="white", width=820, height=560, highlightthickness=1, highlightbackground="#c8ccd0")
        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        side = ttk.Frame(body, width=270)
        side.pack(side=tk.RIGHT, fill=tk.Y, padx=(10, 0))
        ttk.Label(side, text="Parameter context").pack(anchor=tk.W)
        self.context_text = tk.Text(side, width=38, height=24, wrap=tk.WORD)
        self.context_text.pack(fill=tk.BOTH, expand=True, pady=(4, 8))
        self.context_text.configure(state=tk.DISABLED)
        ttk.Label(side, textvariable=self.status_var, wraplength=260).pack(anchor=tk.W)

        if initial_dir:
            self.load()

    def add_combo(
        self,
        parent: object,
        label: str,
        variable: object,
        values: list[str],
        callback: Callable[[object], None] | None,
        width: int = 16,
    ) -> object:
        frame = self.ttk.Frame(parent)
        frame.pack(side=self.tk.LEFT, padx=(0, 8))
        self.ttk.Label(frame, text=label).pack(anchor=self.tk.W)
        combo = self.ttk.Combobox(frame, textvariable=variable, values=values, state="readonly", width=width)
        combo.pack(anchor=self.tk.W)
        if values and not variable.get():
            variable.set(values[0])
        if callback:
            combo.bind("<<ComboboxSelected>>", callback)
        return combo

    def browse(self) -> None:
        from tkinter import filedialog

        directory = filedialog.askdirectory(title="Select DSE output directory")
        if directory:
            self.input_var.set(directory)

    def load(self) -> None:
        try:
            self.dataset = load_dataset(Path(self.input_var.get()))
            self.experiment_combo.configure(values=["All"] + self.dataset.experiments)
            self.experiment_var.set("All")
            self.x_combo.configure(values=self.dataset.parameter_names)
            self.x_param_var.set(default_x_parameter(self.dataset))
            self.on_report_change()
            self.status_var.set(f"Loaded {len(self.dataset.runs)} runs from {self.dataset.root}")
            self.preview()
        except Exception as exc:
            self.status_var.set(str(exc))

    def on_report_change(self, _event: object | None = None) -> None:
        report_type = self.report_var.get()
        metrics = available_metrics(report_type)
        labels = [metric.label for metric in metrics]
        self.metric_label_to_key = {metric.label: metric.key for metric in metrics}
        self.metric_combo.configure(values=labels)
        if labels:
            default_key = default_metric(report_type)
            default_label = next((metric.label for metric in metrics if metric.key == default_key), labels[0])
            self.metric_var.set(default_label)

    def build_spec(self) -> ChartSpec:
        if self.dataset is None:
            raise ValueError("No DSE directory loaded.")
        metric_key = self.metric_label_to_key.get(self.metric_var.get(), self.metric_var.get())
        return ChartSpec(
            report_type=self.report_var.get(),
            metric_key=metric_key,
            x_param=self.x_param_var.get(),
            graph_type=self.graph_var.get(),
            aggregation=self.aggregation_var.get(),
            experiment=self.experiment_var.get(),
            filters=parse_filters(None, self.filters_var.get()),
            title=self.title_var.get(),
            font_size=self.font_var.get(),
        )

    def preview(self) -> None:
        try:
            if self.dataset is None:
                raise ValueError("No DSE directory loaded.")
            spec = self.build_spec()
            self.current_data = prepare_chart_data(self.dataset, spec)
            draw_tk_chart(self.canvas, self.current_data, spec.graph_type)
            self.update_context(self.current_data)
            self.status_var.set(f"Previewing {len(self.current_data.labels)} groups.")
        except Exception as exc:
            self.status_var.set(str(exc))

    def update_context(self, data: ChartData) -> None:
        self.context_text.configure(state=self.tk.NORMAL)
        self.context_text.delete("1.0", self.tk.END)
        self.context_text.insert(self.tk.END, "\n".join(data.parameter_summary))
        if data.warnings:
            self.context_text.insert(self.tk.END, "\n\nBackground variation is aggregated in this figure.")
        self.context_text.configure(state=self.tk.DISABLED)

    def export(self) -> None:
        from tkinter import filedialog

        try:
            if self.dataset is None:
                raise ValueError("No DSE directory loaded.")
            spec = self.build_spec()
            data = prepare_chart_data(self.dataset, spec)
            default_name = sanitize_filename(data.title) + ".pdf"
            path = filedialog.asksaveasfilename(
                title="Export PDF",
                defaultextension=".pdf",
                initialfile=default_name,
                filetypes=[("PDF", "*.pdf")],
            )
            if not path:
                return
            export_pdf(Path(path), data, spec.graph_type, spec.font_size)
            self.status_var.set(f"Exported {path}")
        except Exception as exc:
            self.status_var.set(str(exc))


def draw_tk_chart(canvas: object, data: ChartData, graph_type: str) -> None:
    canvas.delete("all")
    width = int(canvas.winfo_width() or 820)
    height = int(canvas.winfo_height() or 560)
    canvas.create_text(24, 24, text=data.title, anchor="w", font=("Helvetica", 16, "bold"), fill="#151719")
    canvas.create_text(24, 48, text=data.subtitle, anchor="w", font=("Helvetica", 9), fill="#4d5358")

    if graph_type == "pie":
        draw_tk_pie(canvas, data, width, height)
    else:
        draw_tk_xy(canvas, data, graph_type, width, height)


def draw_tk_xy(canvas: object, data: ChartData, graph_type: str, width: int, height: int) -> None:
    left, top, right, bottom = 78, 88, width - 36, height - 86
    y_min, y_max = padded_y_range(data.values)
    canvas.create_rectangle(left, top, right, bottom, fill="#fbfbfa", outline="#c8ccd0")

    for tick in range(6):
        ratio = tick / 5
        y = bottom - ratio * (bottom - top)
        value = y_min + ratio * (y_max - y_min)
        canvas.create_line(left, y, right, y, fill="#dde1e5")
        canvas.create_text(left - 8, y, text=nice_number(value), anchor="e", font=("Helvetica", 8), fill="#4d5358")

    def map_y(value: float) -> float:
        return bottom - (value - y_min) / (y_max - y_min) * (bottom - top)

    n = len(data.labels)
    step = (right - left) / max(1, n)
    centers = [left + step * (index + 0.5) for index in range(n)]

    if graph_type == "bar":
        bar_width = min(54, step * 0.62)
        for index, (label, value) in enumerate(zip(data.labels, data.values)):
            x = centers[index]
            y = map_y(value)
            color = rgb_to_hex(PALETTE[index % len(PALETTE)])
            canvas.create_rectangle(x - bar_width / 2, y, x + bar_width / 2, bottom, fill=color, outline=color)
            canvas.create_text(x, y - 10, text=nice_number(value), font=("Helvetica", 8), fill="#151719")
            canvas.create_text(x, bottom + 16, text=label[:16], font=("Helvetica", 8), fill="#33383d")
    else:
        points = [(centers[index], map_y(value)) for index, value in enumerate(data.values)]
        if graph_type == "line" and len(points) > 1:
            flat_points = [coordinate for point in points for coordinate in point]
            canvas.create_line(*flat_points, fill=rgb_to_hex(PALETTE[0]), width=2)
        for index, (x, y) in enumerate(points):
            color = rgb_to_hex(PALETTE[index % len(PALETTE)] if graph_type == "scatter" else PALETTE[0])
            canvas.create_oval(x - 5, y - 5, x + 5, y + 5, fill=color, outline="white", width=1)
            canvas.create_text(x, y - 14, text=nice_number(data.values[index]), font=("Helvetica", 8), fill="#151719")
            canvas.create_text(x, bottom + 16, text=data.labels[index][:16], font=("Helvetica", 8), fill="#33383d")

    canvas.create_text((left + right) / 2, height - 28, text=data.x_label, font=("Helvetica", 10, "bold"), fill="#151719")
    canvas.create_text(16, (top + bottom) / 2, text=data.metric_label, anchor="w", font=("Helvetica", 9, "bold"), fill="#151719")


def draw_tk_pie(canvas: object, data: ChartData, width: int, height: int) -> None:
    total = sum(max(0.0, value) for value in data.values)
    if total <= 0:
        canvas.create_text(120, 180, text="Pie chart requires positive values.", anchor="w", fill="#b82e2e")
        return

    cx, cy, radius = width * 0.38, height * 0.48, min(width, height) * 0.24
    start = 90.0
    for index, value in enumerate(data.values):
        extent = -360.0 * max(0.0, value) / total
        canvas.create_arc(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            start=start,
            extent=extent,
            fill=rgb_to_hex(PALETTE[index % len(PALETTE)]),
            outline="white",
        )
        start += extent

    legend_x, legend_y = width * 0.66, height * 0.28
    for index, (label, value) in enumerate(zip(data.labels, data.values)):
        y = legend_y + index * 24
        color = rgb_to_hex(PALETTE[index % len(PALETTE)])
        canvas.create_rectangle(legend_x, y, legend_x + 12, y + 12, fill=color, outline=color)
        percent = 100.0 * max(0.0, value) / total
        canvas.create_text(
            legend_x + 18,
            y + 6,
            text=f"{label}: {nice_number(value)} ({percent:.1f}%)",
            anchor="w",
            font=("Helvetica", 9),
            fill="#151719",
        )


def svg_chart(data: ChartData, graph_type: str, width: int = 820, height: int = 520) -> str:
    import html

    parts = [
        f'<svg viewBox="0 0 {width} {height}" width="100%" height="100%" role="img" '
        f'aria-label="{html.escape(data.title)}" xmlns="http://www.w3.org/2000/svg">',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        f'<text x="24" y="30" font-family="Helvetica, Arial, sans-serif" font-size="18" '
        f'font-weight="700" fill="#151719">{html.escape(data.title)}</text>',
        f'<text x="24" y="52" font-family="Helvetica, Arial, sans-serif" font-size="11" '
        f'fill="#4d5358">{html.escape(data.subtitle)}</text>',
    ]

    if graph_type == "pie":
        parts.extend(svg_pie(data, width, height))
    else:
        parts.extend(svg_xy(data, graph_type, width, height))
    parts.append("</svg>")
    return "\n".join(parts)


def svg_xy(data: ChartData, graph_type: str, width: int, height: int) -> list[str]:
    import html

    left, top, right, bottom = 82.0, 88.0, width - 38.0, height - 74.0
    plot_width = right - left
    plot_height = bottom - top
    y_min, y_max = padded_y_range(data.values)

    def map_y(value: float) -> float:
        return bottom - (value - y_min) / (y_max - y_min) * plot_height

    parts = [
        f'<rect x="{left:.2f}" y="{top:.2f}" width="{plot_width:.2f}" height="{plot_height:.2f}" '
        'fill="#fbfbfa" stroke="#c8ccd0"/>'
    ]
    for tick in range(6):
        ratio = tick / 5
        y = bottom - ratio * plot_height
        value = y_min + ratio * (y_max - y_min)
        parts.append(f'<line x1="{left:.2f}" y1="{y:.2f}" x2="{right:.2f}" y2="{y:.2f}" stroke="#dde1e5"/>')
        parts.append(
            f'<text x="{left - 8:.2f}" y="{y + 4:.2f}" text-anchor="end" '
            'font-family="Helvetica, Arial, sans-serif" font-size="10" fill="#4d5358">'
            f'{html.escape(nice_number(value))}</text>'
        )

    n = len(data.labels)
    step = plot_width / max(1, n)
    centers = [left + step * (index + 0.5) for index in range(n)]

    if graph_type == "bar":
        bar_width = min(58.0, step * 0.62)
        for index, (label, value) in enumerate(zip(data.labels, data.values)):
            x = centers[index] - bar_width / 2.0
            y = map_y(value)
            color = rgb_to_hex(PALETTE[index % len(PALETTE)])
            parts.append(
                f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_width:.2f}" height="{max(0.5, bottom - y):.2f}" '
                f'fill="{color}"/>'
            )
            parts.append(svg_value_label(centers[index], y - 8, nice_number(value)))
            parts.append(svg_axis_label(centers[index], bottom + 18, label[:18]))
    else:
        points = [(centers[index], map_y(value)) for index, value in enumerate(data.values)]
        if graph_type == "line" and len(points) > 1:
            point_text = " ".join(f"{x:.2f},{y:.2f}" for x, y in points)
            parts.append(f'<polyline points="{point_text}" fill="none" stroke="{rgb_to_hex(PALETTE[0])}" stroke-width="2.4"/>')
        for index, (x, y) in enumerate(points):
            color = rgb_to_hex(PALETTE[index % len(PALETTE)] if graph_type == "scatter" else PALETTE[0])
            parts.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="5" fill="{color}" stroke="#ffffff"/>')
            parts.append(svg_value_label(x, y - 12, nice_number(data.values[index])))
            parts.append(svg_axis_label(x, bottom + 18, data.labels[index][:18]))

    parts.append(f'<line x1="{left:.2f}" y1="{bottom:.2f}" x2="{right:.2f}" y2="{bottom:.2f}" stroke="#151719"/>')
    parts.append(f'<line x1="{left:.2f}" y1="{top:.2f}" x2="{left:.2f}" y2="{bottom:.2f}" stroke="#151719"/>')
    parts.append(
        f'<text x="{(left + right) / 2:.2f}" y="{height - 28:.2f}" text-anchor="middle" '
        'font-family="Helvetica, Arial, sans-serif" font-size="12" font-weight="700" fill="#151719">'
        f'{html.escape(data.x_label)}</text>'
    )
    parts.append(
        f'<text x="18" y="{(top + bottom) / 2:.2f}" font-family="Helvetica, Arial, sans-serif" '
        f'font-size="11" font-weight="700" fill="#151719">{html.escape(data.metric_label)}</text>'
    )
    return parts


def svg_pie(data: ChartData, width: int, height: int) -> list[str]:
    import html

    total = sum(max(0.0, value) for value in data.values)
    if total <= 0:
        return [
            '<text x="120" y="190" font-family="Helvetica, Arial, sans-serif" font-size="14" '
            'fill="#b82e2e">Pie chart requires positive values.</text>'
        ]

    cx, cy, radius = width * 0.38, height * 0.52, min(width, height) * 0.24
    start_angle = math.pi / 2
    parts: list[str] = []
    for index, value in enumerate(data.values):
        fraction = max(0.0, value) / total
        end_angle = start_angle - 2.0 * math.pi * fraction
        points = [(cx, cy)]
        steps = max(4, int(50 * fraction))
        for step in range(steps + 1):
            angle = start_angle + (end_angle - start_angle) * step / steps
            points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
        points_text = " ".join(f"{x:.2f},{y:.2f}" for x, y in points)
        parts.append(f'<polygon points="{points_text}" fill="{rgb_to_hex(PALETTE[index % len(PALETTE)])}" stroke="#ffffff"/>')
        start_angle = end_angle

    legend_x, legend_y = width * 0.66, height * 0.30
    for index, (label, value) in enumerate(zip(data.labels, data.values)):
        y = legend_y + index * 26
        percent = 100.0 * max(0.0, value) / total
        color = rgb_to_hex(PALETTE[index % len(PALETTE)])
        parts.append(f'<rect x="{legend_x:.2f}" y="{y:.2f}" width="12" height="12" fill="{color}"/>')
        parts.append(
            f'<text x="{legend_x + 18:.2f}" y="{y + 11:.2f}" font-family="Helvetica, Arial, sans-serif" '
            f'font-size="12" fill="#151719">{html.escape(label)}: {html.escape(nice_number(value))} '
            f'({percent:.1f}%)</text>'
        )
    return parts


def svg_value_label(x: float, y: float, value: str) -> str:
    import html

    return (
        f'<text x="{x:.2f}" y="{y:.2f}" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" '
        f'font-size="10" fill="#151719">{html.escape(value)}</text>'
    )


def svg_axis_label(x: float, y: float, value: str) -> str:
    import html

    return (
        f'<text x="{x:.2f}" y="{y:.2f}" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" '
        f'font-size="10" fill="#33383d">{html.escape(value)}</text>'
    )


def run_web_gui(initial_dir: Path | None, reason: str | None = None) -> int:
    import html
    import tempfile
    import webbrowser
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, quote, urlencode, urlparse

    class WebHandler(BaseHTTPRequestHandler):
        def log_message(self, _format: str, *_args: object) -> None:
            return

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            params = {key: values[-1] for key, values in parse_qs(parsed.query).items()}
            if parsed.path == "/pdf":
                self.write_pdf(params)
                return
            self.write_html(params)

        def write_html(self, params: dict[str, str]) -> None:
            body = render_web_page(params, reason)
            encoded = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def write_pdf(self, params: dict[str, str]) -> None:
            try:
                dataset, spec, data = build_web_selection(params)
                with tempfile.NamedTemporaryFile(prefix="uv-dse-figure.", suffix=".pdf", delete=False) as tmp:
                    pdf_path = Path(tmp.name)
                export_pdf(pdf_path, data, spec.graph_type, spec.font_size)
                content = pdf_path.read_bytes()
                pdf_path.unlink(missing_ok=True)
                filename = sanitize_filename(data.title) + ".pdf"
                self.send_response(200)
                self.send_header("Content-Type", "application/pdf")
                self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
            except Exception as exc:
                params["error"] = str(exc)
                self.write_html(params)

    def render_web_page(params: dict[str, str], fallback_reason: str | None) -> str:
        dataset: DseDataset | None = None
        data: ChartData | None = None
        spec: ChartSpec | None = None
        error = params.get("error", "")
        try:
            if params.get("input_dir"):
                dataset, spec, data = build_web_selection(params)
        except Exception as exc:
            error = str(exc)

        report = params.get("report", "network")
        if report not in {"network", "invoice"}:
            report = "network"
        metrics = available_metrics(report)
        metric_key = params.get("metric")
        if metric_key not in {metric.key for metric in metrics}:
            metric_key = default_metric(report)
        input_dir = params.get("input_dir", str(initial_dir) if initial_dir else "")
        x_param = spec.x_param if spec else params.get("x_param", "")
        experiment = spec.experiment if spec else params.get("experiment", "All")
        graph = spec.graph_type if spec else params.get("graph", "bar")
        aggregation = spec.aggregation if spec else params.get("aggregation", "mean")
        title = spec.title if spec else params.get("title", "")
        font_size = spec.font_size if spec else parse_int(params.get("font_size"), 10)
        filter_text = params.get("filter", "")

        parameter_options = dataset.parameter_names if dataset else []
        experiment_options = ["All"] + dataset.experiments if dataset else ["All"]
        figure = svg_chart(data, graph) if data else empty_svg()
        context = data.parameter_summary if data else []
        warning_lines = data.warnings if data else []
        pdf_query = urlencode(
            {
                "input_dir": input_dir,
                "report": report,
                "metric": metric_key,
                "x_param": x_param,
                "experiment": experiment,
                "graph": graph,
                "aggregation": aggregation,
                "filter": filter_text,
                "title": title,
                "font_size": str(font_size),
            }
        )

        fallback_banner = ""
        if fallback_reason:
            fallback_banner = f'<div class="notice">{html.escape(fallback_reason)}</div>'
        error_banner = f'<div class="error">{html.escape(error)}</div>' if error else ""

        return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>UltraViolet DSE Visualizer</title>
  <style>
    body {{ margin: 0; font-family: Helvetica, Arial, sans-serif; color: #151719; background: #f4f5f6; }}
    header {{ padding: 14px 18px; background: #ffffff; border-bottom: 1px solid #d8dce0; }}
    h1 {{ margin: 0; font-size: 18px; }}
    main {{ padding: 14px 18px; }}
    form {{ display: grid; grid-template-columns: repeat(6, minmax(120px, 1fr)); gap: 10px; align-items: end; }}
    label {{ display: grid; gap: 4px; font-size: 12px; font-weight: 700; color: #33383d; }}
    input, select {{ font: inherit; font-size: 13px; padding: 7px; border: 1px solid #b9c0c7; background: #ffffff; }}
    .span3 {{ grid-column: span 3; }}
    .span2 {{ grid-column: span 2; }}
    button, .button {{ border: 1px solid #174f91; background: #1a5fa8; color: #ffffff; padding: 8px 10px; font-weight: 700; text-decoration: none; text-align: center; }}
    .content {{ display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 14px; margin-top: 14px; }}
    .panel {{ background: #ffffff; border: 1px solid #d8dce0; padding: 12px; }}
    .figure {{ height: 560px; }}
    .notice {{ margin-top: 10px; padding: 9px; background: #fff8e6; border: 1px solid #e2c56f; color: #4d3b00; }}
    .error {{ margin-top: 10px; padding: 9px; background: #fdeaea; border: 1px solid #e2a0a0; color: #7b1d1d; }}
    ul {{ padding-left: 18px; }}
    li {{ margin-bottom: 6px; }}
  </style>
</head>
<body>
  <header><h1>UltraViolet DSE Visualizer</h1>{fallback_banner}{error_banner}</header>
  <main>
    <form method="get" action="/">
      <label class="span3">DSE output directory
        <input name="input_dir" value="{html.escape(input_dir)}">
      </label>
      <label>Report
        {select_html("report", ["network", "invoice"], report)}
      </label>
      <label class="span2">Metric
        {select_html("metric", [metric.key for metric in metrics], metric_key, {metric.key: metric.label for metric in metrics})}
      </label>
      <label>X parameter
        {select_html("x_param", parameter_options, x_param)}
      </label>
      <label>Experiment
        {select_html("experiment", experiment_options, experiment)}
      </label>
      <label>Graph
        {select_html("graph", GRAPH_TYPES, graph)}
      </label>
      <label>Aggregation
        {select_html("aggregation", AGGREGATIONS, aggregation)}
      </label>
      <label class="span2">Fixed filters
        <input name="filter" value="{html.escape(filter_text)}" placeholder="seed=1, bootstrap_nodes=100">
      </label>
      <label class="span2">Title
        <input name="title" value="{html.escape(title)}">
      </label>
      <label>PDF font size
        <input name="font_size" type="number" min="8" max="18" value="{font_size}">
      </label>
      <button type="submit">Preview</button>
      <a class="button" href="/pdf?{html.escape(pdf_query)}">Create PDF</a>
    </form>
    <section class="content">
      <div class="panel figure">{figure}</div>
      <aside class="panel">
        <h2>Parameter context</h2>
        {context_html(context)}
        {warnings_html(warning_lines)}
      </aside>
    </section>
  </main>
</body>
</html>"""

    def build_web_selection(params: dict[str, str]) -> tuple[DseDataset, ChartSpec, ChartData]:
        dataset = load_dataset(Path(params.get("input_dir", str(initial_dir) if initial_dir else "")))
        spec = chart_spec_from_params(dataset, params)
        data = prepare_chart_data(dataset, spec)
        return dataset, spec, data

    def select_html(name: str, options: list[str], selected: str, labels: dict[str, str] | None = None) -> str:
        labels = labels or {}
        option_html = []
        for option in options:
            selected_attr = " selected" if option == selected else ""
            option_html.append(
                f'<option value="{html.escape(option)}"{selected_attr}>{html.escape(labels.get(option, option))}</option>'
            )
        if not option_html:
            option_html.append('<option value="">Load a DSE directory first</option>')
        return f'<select name="{html.escape(name)}">{"".join(option_html)}</select>'

    def context_html(lines: list[str]) -> str:
        if not lines:
            return "<p>Load a DSE output directory and preview a figure.</p>"
        return "<ul>" + "".join(f"<li>{html.escape(line)}</li>" for line in lines) + "</ul>"

    def warnings_html(lines: list[str]) -> str:
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

    server = ThreadingHTTPServer(("127.0.0.1", 0), WebHandler)
    query = f"?input_dir={quote(str(initial_dir))}" if initial_dir else ""
    url = f"http://127.0.0.1:{server.server_address[1]}/{query}"
    if reason:
        print(reason, flush=True)
    print(f"Starting browser GUI at {url}", flush=True)
    print("Press Ctrl-C to stop the visualizer server.", flush=True)
    webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping visualizer server.", flush=True)
    finally:
        server.server_close()
    return 0


def sanitize_filename(value: str) -> str:
    cleaned = "".join(ch if ch.isalnum() else "-" for ch in value.lower()).strip("-")
    while "--" in cleaned:
        cleaned = cleaned.replace("--", "-")
    return cleaned or "uv-dse-figure"


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Visualize UltraViolet DSE outputs and export publication-ready PDFs.")
    parser.add_argument("input_dir", nargs="?", help="DSE output directory produced by uv_dse_run")
    parser.add_argument("--no-gui", action="store_true", help="Run a headless PDF export instead of opening the GUI")
    parser.add_argument("--web", action="store_true", help="Open the browser GUI instead of the Tk desktop GUI")
    parser.add_argument("--report", choices=["network", "invoice"], default="network")
    parser.add_argument("--metric", help="Metric key. Use --list-metrics to inspect available keys.")
    parser.add_argument("--x-param", help="Parameter to place on the x-axis")
    parser.add_argument("--experiment", default="All", help="Experiment name to include, or All")
    parser.add_argument("--graph", choices=GRAPH_TYPES, default="bar")
    parser.add_argument("--aggregation", choices=AGGREGATIONS, default="mean")
    parser.add_argument("--filter", action="append", help="Fixed parameter filter as key=value. Can be repeated.")
    parser.add_argument("--title", default="")
    parser.add_argument("--font-size", type=int, default=10)
    parser.add_argument("--output-pdf", help="PDF output path for --no-gui")
    parser.add_argument("--list-metrics", action="store_true", help="Print metric keys for the selected report type")
    return parser


def main() -> int:
    args = build_arg_parser().parse_args()

    if args.list_metrics:
        for metric in available_metrics(args.report):
            print(f"{metric.key}\t{metric.label}")
        return 0

    if args.no_gui:
        if not args.input_dir:
            print("--no-gui requires input_dir", file=sys.stderr)
            return 1
        if not args.output_pdf:
            print("--no-gui requires --output-pdf", file=sys.stderr)
            return 1
        try:
            dataset = load_dataset(Path(args.input_dir))
            report_type = args.report
            spec = ChartSpec(
                report_type=report_type,
                metric_key=args.metric or default_metric(report_type),
                x_param=args.x_param or default_x_parameter(dataset),
                graph_type=args.graph,
                aggregation=args.aggregation,
                experiment=args.experiment,
                filters=parse_filters(args.filter),
                title=args.title,
                font_size=args.font_size,
            )
            data = prepare_chart_data(dataset, spec)
            export_pdf(Path(args.output_pdf), data, spec.graph_type, spec.font_size)
            print(f"Exported {args.output_pdf}")
            print(f"Groups: {', '.join(data.labels)}")
            if data.warnings:
                print("Background variation was aggregated:")
                for warning in data.warnings:
                    print(f"- {warning}")
            return 0
        except Exception as exc:
            print(str(exc), file=sys.stderr)
            return 1

    if args.web:
        return run_web_gui(Path(args.input_dir) if args.input_dir else None)

    try:
        import tkinter as tk
    except ModuleNotFoundError as exc:
        if exc.name == "_tkinter":
            reason = (
                "Tkinter is not available in this Python installation. "
                "Falling back to the browser GUI; use --web to request this mode explicitly."
            )
            return run_web_gui(Path(args.input_dir) if args.input_dir else None, reason)
        raise

    root = tk.Tk()
    DseVisualizerGui(root, Path(args.input_dir) if args.input_dir else None)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
