#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import math
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
INT_FIELDS = {
    "amt",
    "max_fees",
    "search_limit_paths",
    "search_returned_paths",
    "search_investigated_states",
    "search_expanded_edges",
    "search_excluded_capacity",
    "search_excluded_visited_state",
    "search_excluded_cycle",
    "search_excluded_max_hops",
    "search_excluded_cost",
    "filtered_policy",
    "filtered_capacity",
    "filtered_local_liquidity",
    "filtered_max_fees",
    "candidate_paths",
    "attempted_paths",
    "attempt_failed_temporary_channel",
    "attempt_failed_expiry_too_soon",
    "attempt_failed_local_liquidity",
    "attempt_failed_timeout",
    "attempt_failed_unknown",
}

REQUIRED_FIELDS = {
    "hash",
    "sender",
    "dest",
    "amt",
    "max_fees",
    "path_finder",
    "search_limit_paths",
    "search_returned_paths",
    "search_investigated_states",
    "search_expanded_edges",
    "search_excluded_capacity",
    "search_excluded_visited_state",
    "search_excluded_cycle",
    "search_excluded_max_hops",
    "search_excluded_cost",
    "filtered_policy",
    "filtered_capacity",
    "filtered_local_liquidity",
    "filtered_max_fees",
    "candidate_paths",
    "attempted_paths",
    "attempt_failed_temporary_channel",
    "attempt_failed_expiry_too_soon",
    "attempt_failed_local_liquidity",
    "attempt_failed_timeout",
    "attempt_failed_unknown",
    "success",
}

GROUP_COLORS = [
    (0.10, 0.31, 0.57),
    (0.75, 0.29, 0.13),
    (0.12, 0.48, 0.34),
    (0.83, 0.60, 0.12),
    (0.56, 0.20, 0.20),
    (0.18, 0.55, 0.60),
    (0.32, 0.32, 0.32),
    (0.49, 0.43, 0.18),
]

OUTCOME_COLORS = {
    "success": (0.15, 0.58, 0.30),
    "search_no_path": (0.10, 0.31, 0.57),
    "filtered_out": (0.85, 0.48, 0.18),
    "candidate_not_attempted": (0.55, 0.55, 0.55),
    "attempt_failed": (0.72, 0.18, 0.18),
}

FILTER_COLORS = {
    "filtered_policy": (0.58, 0.42, 0.16),
    "filtered_capacity": (0.83, 0.60, 0.12),
    "filtered_local_liquidity": (0.75, 0.29, 0.13),
    "filtered_max_fees": (0.56, 0.20, 0.20),
    "kept_as_candidate": (0.15, 0.58, 0.30),
}

ATTEMPT_COLORS = {
    "success_path": (0.15, 0.58, 0.30),
    "temporary_channel_failure": (0.83, 0.60, 0.12),
    "expiry_too_soon": (0.75, 0.29, 0.13),
    "local_liquidity_failure": (0.58, 0.42, 0.16),
    "timeout": (0.10, 0.31, 0.57),
    "unknown": (0.55, 0.55, 0.55),
}

SEARCH_MIX_COLORS = {
    "capacity": (0.83, 0.60, 0.12),
    "visited_state": (0.10, 0.31, 0.57),
    "cycle": (0.18, 0.55, 0.60),
    "max_hops": (0.75, 0.29, 0.13),
    "cost": (0.55, 0.55, 0.55),
}


@dataclass(frozen=True)
class InvoiceRecord:
    source_label: str
    hash: str
    sender: str
    dest: str
    amt: int
    max_fees: int
    path_finder: str
    search_limit_paths: int
    search_returned_paths: int
    search_investigated_states: int
    search_expanded_edges: int
    search_excluded_capacity: int
    search_excluded_visited_state: int
    search_excluded_cycle: int
    search_excluded_max_hops: int
    search_excluded_cost: int
    filtered_policy: int
    filtered_capacity: int
    filtered_local_liquidity: int
    filtered_max_fees: int
    candidate_paths: int
    attempted_paths: int
    attempt_failed_temporary_channel: int
    attempt_failed_expiry_too_soon: int
    attempt_failed_local_liquidity: int
    attempt_failed_timeout: int
    attempt_failed_unknown: int
    success: bool


@dataclass
class Group:
    label: str
    records: list[InvoiceRecord]
    color: tuple[float, float, float]


@dataclass
class PlotArea:
    left: float
    bottom: float
    width: float
    height: float
    x_min: float
    x_max: float
    y_min: float
    y_max: float

    def map_x(self, value: float) -> float:
        if self.x_max == self.x_min:
            return self.left + self.width / 2.0
        return self.left + (value - self.x_min) / (self.x_max - self.x_min) * self.width

    def map_y(self, value: float) -> float:
        if self.y_max == self.y_min:
            return self.bottom + self.height / 2.0
        return self.bottom + (value - self.y_min) / (self.y_max - self.y_min) * self.height


class PdfCanvas:
    def __init__(self, width: float = 792.0, height: float = 540.0) -> None:
        self.width = width
        self.height = height
        self._commands: list[str] = []

    def _add(self, command: str) -> None:
        self._commands.append(command)

    def set_stroke_color(self, rgb: tuple[float, float, float]) -> None:
        self._add(f"{rgb[0]:.3f} {rgb[1]:.3f} {rgb[2]:.3f} RG")

    def set_fill_color(self, rgb: tuple[float, float, float]) -> None:
        self._add(f"{rgb[0]:.3f} {rgb[1]:.3f} {rgb[2]:.3f} rg")

    def set_line_width(self, width: float) -> None:
        self._add(f"{width:.3f} w")

    def line(self, x1: float, y1: float, x2: float, y2: float) -> None:
        self._add(f"{x1:.2f} {y1:.2f} m {x2:.2f} {y2:.2f} l S")

    def rect(
        self,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        stroke: bool = True,
        fill: bool = False,
    ) -> None:
        op = "B" if stroke and fill else "S" if stroke else "f"
        self._add(f"{x:.2f} {y:.2f} {width:.2f} {height:.2f} re {op}")

    def polyline(self, points: list[tuple[float, float]]) -> None:
        if len(points) < 2:
            return
        commands = [f"{points[0][0]:.2f} {points[0][1]:.2f} m"]
        for x, y in points[1:]:
            commands.append(f"{x:.2f} {y:.2f} l")
        commands.append("S")
        self._add(" ".join(commands))

    def polygon(
        self,
        points: list[tuple[float, float]],
        *,
        stroke: bool = True,
        fill: bool = False,
    ) -> None:
        if len(points) < 3:
            return
        commands = [f"{points[0][0]:.2f} {points[0][1]:.2f} m"]
        for x, y in points[1:]:
            commands.append(f"{x:.2f} {y:.2f} l")
        commands.append("h")
        commands.append("B" if stroke and fill else "S" if stroke else "f")
        self._add(" ".join(commands))

    def circle(
        self,
        x: float,
        y: float,
        radius: float,
        *,
        stroke: bool = True,
        fill: bool = False,
        segments: int = 18,
    ) -> None:
        points = []
        for index in range(segments):
            angle = 2.0 * math.pi * index / segments
            points.append((x + radius * math.cos(angle), y + radius * math.sin(angle)))
        self.polygon(points, stroke=stroke, fill=fill)

    def text(
        self,
        x: float,
        y: float,
        value: str,
        *,
        size: float = 12.0,
        align: str = "left",
        angle: float = 0.0,
        font: str = "F1",
    ) -> None:
        text = escape_pdf_text(value)
        width = approximate_text_width(value, size)
        x_pos = x
        if align == "center":
            x_pos -= width / 2.0
        elif align == "right":
            x_pos -= width
        radians = math.radians(angle)
        a = math.cos(radians)
        b = math.sin(radians)
        c = -math.sin(radians)
        d = math.cos(radians)
        self._add(
            f"BT /{font} {size:.2f} Tf {a:.5f} {b:.5f} {c:.5f} {d:.5f} {x_pos:.2f} {y:.2f} Tm ({text}) Tj ET"
        )

    def save(self, output_path: Path) -> None:
        stream = "\n".join(self._commands).encode("latin-1")
        objects = [
            b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            (
                f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {self.width:.0f} {self.height:.0f}] "
                f"/Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>"
            ).encode("latin-1"),
            b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"\nendstream",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
        ]

        pdf = bytearray()
        pdf.extend(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for index, obj in enumerate(objects, start=1):
            offsets.append(len(pdf))
            pdf.extend(f"{index} 0 obj\n".encode("ascii"))
            pdf.extend(obj)
            pdf.extend(b"\nendobj\n")
        xref_offset = len(pdf)
        pdf.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
        pdf.extend(b"0000000000 65535 f \n")
        for offset in offsets[1:]:
            pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
        pdf.extend(
            (
                f"trailer << /Size {len(objects) + 1} /Root 1 0 R >>\n"
                f"startxref\n{xref_offset}\n%%EOF\n"
            ).encode("ascii")
        )
        output_path.write_bytes(pdf)


def escape_pdf_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def approximate_text_width(value: str, size: float) -> float:
    return max(0.0, len(value) * size * 0.52)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate publication-oriented PDF figures from UltraViolet invoice report CSV files."
    )
    parser.add_argument("reports", nargs="+", help="Invoice report CSV file(s).")
    parser.add_argument(
        "-o",
        "--output-dir",
        default="invoice_report_figures",
        help="Directory where PDF figures will be written.",
    )
    parser.add_argument(
        "--amount-bins",
        type=int,
        default=10,
        help="Number of amount bins for success-rate curves.",
    )
    parser.add_argument(
        "--scatter-limit",
        type=int,
        default=1500,
        help="Maximum number of points plotted in the scatter figure.",
    )
    parser.add_argument(
        "--title-prefix",
        default="Invoice Report",
        help="Short prefix prepended to figure titles.",
    )
    return parser.parse_args()


def infer_source_label(path: Path) -> str:
    name = path.name
    marker = "_invoice."
    if marker in name:
        return name.split(marker, 1)[0]
    return path.stem


def load_records(report_paths: list[Path]) -> list[InvoiceRecord]:
    records: list[InvoiceRecord] = []
    for report_path in report_paths:
        with report_path.open(newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                raise ValueError(f"{report_path} has no CSV header")
            missing = REQUIRED_FIELDS.difference(reader.fieldnames)
            if missing:
                raise ValueError(f"{report_path} is missing required columns: {', '.join(sorted(missing))}")
            source_label = infer_source_label(report_path)
            for row in reader:
                normalized: dict[str, object] = {"source_label": source_label}
                for key, value in row.items():
                    if key in INT_FIELDS:
                        normalized[key] = int(value)
                    elif key == "success":
                        normalized[key] = value.strip().lower() == "true"
                    else:
                        normalized[key] = value.strip()
                records.append(InvoiceRecord(**normalized))
    if not records:
        raise ValueError("No invoice rows found in the provided report files.")
    return records


def build_groups(records: list[InvoiceRecord]) -> list[Group]:
    source_count = len({record.source_label for record in records})
    strategy_count = len({record.path_finder for record in records})
    grouped: dict[str, list[InvoiceRecord]] = defaultdict(list)
    for record in records:
        if source_count > 1 and strategy_count > 1:
            label = f"{record.source_label}:{record.path_finder}"
        elif source_count > 1:
            label = record.source_label
        else:
            label = record.path_finder
        grouped[label].append(record)
    labels = sorted(grouped)
    groups = []
    for index, label in enumerate(labels):
        groups.append(Group(label, grouped[label], GROUP_COLORS[index % len(GROUP_COLORS)]))
    return groups


def quantile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def humanize_number(value: float) -> str:
    absolute = abs(value)
    if absolute >= 1_000_000:
        return f"{value / 1_000_000:.1f}M"
    if absolute >= 10_000:
        return f"{value / 1_000:.0f}k"
    if absolute >= 1_000:
        return f"{value / 1_000:.1f}k"
    if float(value).is_integer():
        return f"{int(value)}"
    return f"{value:.2f}"


def format_percent(value: float) -> str:
    return f"{value * 100:.0f}%"


def nice_step(span: float, target_ticks: int = 5) -> float:
    if span <= 0:
        return 1.0
    raw = span / max(1, target_ticks - 1)
    power = 10 ** math.floor(math.log10(raw))
    residual = raw / power
    if residual <= 1:
        nice = 1
    elif residual <= 2:
        nice = 2
    elif residual <= 5:
        nice = 5
    else:
        nice = 10
    return nice * power


def nice_ticks(minimum: float, maximum: float, target_ticks: int = 5) -> list[float]:
    if maximum <= minimum:
        return [minimum]
    step = nice_step(maximum - minimum, target_ticks)
    start = math.floor(minimum / step) * step
    end = math.ceil(maximum / step) * step
    ticks = []
    value = start
    while value <= end + step * 0.5:
        ticks.append(round(value, 10))
        value += step
    return ticks


def invoice_outcome(record: InvoiceRecord) -> str:
    if record.success:
        return "success"
    if record.search_returned_paths == 0:
        return "search_no_path"
    if record.candidate_paths == 0:
        return "filtered_out"
    if record.attempted_paths == 0:
        return "candidate_not_attempted"
    return "attempt_failed"


def evenly_downsample(records: list[InvoiceRecord], limit: int) -> list[InvoiceRecord]:
    if limit <= 0 or len(records) <= limit:
        return records
    stride = len(records) / float(limit)
    return [records[int(index * stride)] for index in range(limit)]


def draw_title(canvas: PdfCanvas, title: str, subtitle: str | None = None) -> None:
    canvas.set_fill_color((0.08, 0.08, 0.08))
    canvas.text(396, 506, title, size=18, align="center", font="F2")
    if subtitle:
        canvas.text(396, 486, subtitle, size=10, align="center")


def draw_legend(
    canvas: PdfCanvas,
    x: float,
    y: float,
    items: list[tuple[str, tuple[float, float, float], bool]],
    *,
    title: str | None = None,
) -> None:
    cursor_y = y
    if title:
        canvas.set_fill_color((0.08, 0.08, 0.08))
        canvas.text(x, cursor_y, title, size=11, font="F2")
        cursor_y -= 16
    for label, color, filled in items:
        canvas.set_stroke_color(color)
        canvas.set_fill_color(color if filled else (1.0, 1.0, 1.0))
        canvas.set_line_width(1.0)
        canvas.rect(x, cursor_y - 4, 11, 11, stroke=True, fill=filled)
        if not filled:
            canvas.line(x + 2, cursor_y - 2, x + 9, cursor_y + 5)
        canvas.set_fill_color((0.08, 0.08, 0.08))
        canvas.text(x + 18, cursor_y - 1, label, size=10)
        cursor_y -= 15


def draw_numeric_axes(
    canvas: PdfCanvas,
    area: PlotArea,
    title: str,
    x_label: str,
    y_label: str,
    x_ticks: list[tuple[float, str]],
    y_ticks: list[tuple[float, str]],
) -> None:
    draw_title(canvas, title)
    canvas.set_line_width(0.8)
    canvas.set_stroke_color((0.80, 0.80, 0.80))
    for y_value, label in y_ticks:
        y = area.map_y(y_value)
        canvas.line(area.left, y, area.left + area.width, y)
        canvas.set_fill_color((0.25, 0.25, 0.25))
        canvas.text(area.left - 10, y - 3, label, size=9, align="right")
    for x_value, label in x_ticks:
        x = area.map_x(x_value)
        canvas.line(x, area.bottom, x, area.bottom + area.height)
        canvas.set_fill_color((0.25, 0.25, 0.25))
        canvas.text(x, area.bottom - 18, label, size=9, align="center")
    canvas.set_stroke_color((0.15, 0.15, 0.15))
    canvas.set_line_width(1.2)
    canvas.rect(area.left, area.bottom, area.width, area.height, stroke=True, fill=False)
    canvas.set_fill_color((0.10, 0.10, 0.10))
    canvas.text(area.left + area.width / 2.0, area.bottom - 42, x_label, size=11, align="center")
    canvas.text(area.left - 55, area.bottom + area.height / 2.0, y_label, size=11, align="center", angle=90)


def draw_categorical_y_axis(
    canvas: PdfCanvas,
    area: PlotArea,
    title: str,
    y_label: str,
    y_ticks: list[tuple[float, str]],
) -> None:
    draw_title(canvas, title)
    canvas.set_line_width(0.8)
    canvas.set_stroke_color((0.80, 0.80, 0.80))
    for y_value, label in y_ticks:
        y = area.map_y(y_value)
        canvas.line(area.left, y, area.left + area.width, y)
        canvas.set_fill_color((0.25, 0.25, 0.25))
        canvas.text(area.left - 10, y - 3, label, size=9, align="right")
    canvas.set_stroke_color((0.15, 0.15, 0.15))
    canvas.set_line_width(1.2)
    canvas.rect(area.left, area.bottom, area.width, area.height, stroke=True, fill=False)
    canvas.set_fill_color((0.10, 0.10, 0.10))
    canvas.text(area.left - 55, area.bottom + area.height / 2.0, y_label, size=11, align="center", angle=90)


def rotated_category_labels(canvas: PdfCanvas, positions: list[tuple[float, str]], baseline: float) -> None:
    for x, label in positions:
        angle = 30 if len(label) > 8 else 0
        align = "right" if angle else "center"
        canvas.text(x, baseline, label, size=9, align=align, angle=angle)


def boxplot_stats(values: list[float]) -> dict[str, object]:
    if not values:
        return {
            "q1": 0.0,
            "median": 0.0,
            "q3": 0.0,
            "whisker_low": 0.0,
            "whisker_high": 0.0,
            "outliers": [],
        }
    ordered = sorted(values)
    q1 = quantile(ordered, 0.25)
    median = quantile(ordered, 0.50)
    q3 = quantile(ordered, 0.75)
    iqr = q3 - q1
    low_bound = q1 - 1.5 * iqr
    high_bound = q3 + 1.5 * iqr
    whisker_low = next((value for value in ordered if value >= low_bound), ordered[0])
    whisker_high = next((value for value in reversed(ordered) if value <= high_bound), ordered[-1])
    outliers = [value for value in ordered if value < whisker_low or value > whisker_high]
    if len(outliers) > 24:
        outliers = evenly_select_floats(outliers, 24)
    return {
        "q1": q1,
        "median": median,
        "q3": q3,
        "whisker_low": whisker_low,
        "whisker_high": whisker_high,
        "outliers": outliers,
    }


def evenly_select_floats(values: list[float], limit: int) -> list[float]:
    if limit <= 0 or len(values) <= limit:
        return values
    stride = len(values) / float(limit)
    return [values[int(index * stride)] for index in range(limit)]


def write_invoice_outcomes(groups: list[Group], output_dir: Path, title_prefix: str) -> Path:
    output_path = output_dir / "invoice_outcomes_by_group.pdf"
    canvas = PdfCanvas()
    area = PlotArea(88, 108, 500, 310, 0, max(1, len(groups)), 0.0, 1.0)
    draw_categorical_y_axis(
        canvas,
        area,
        f"{title_prefix}: Invoice Outcomes",
        "Share of invoices",
        [(value, format_percent(value)) for value in [0.0, 0.25, 0.5, 0.75, 1.0]],
    )
    categories = [
        "success",
        "search_no_path",
        "filtered_out",
        "candidate_not_attempted",
        "attempt_failed",
    ]
    bar_width = area.width / max(1, len(groups)) * 0.58
    label_positions: list[tuple[float, str]] = []
    for index, group in enumerate(groups):
        counts = {category: 0 for category in categories}
        for record in group.records:
            counts[invoice_outcome(record)] += 1
        total = max(1, len(group.records))
        x_center = area.left + (index + 0.5) / len(groups) * area.width
        label_positions.append((x_center, group.label))
        x_left = x_center - bar_width / 2.0
        current_y = area.bottom
        for category in categories:
            height = area.height * (counts[category] / total)
            if height <= 0:
                continue
            canvas.set_stroke_color((1.0, 1.0, 1.0))
            canvas.set_fill_color(OUTCOME_COLORS[category])
            canvas.rect(x_left, current_y, bar_width, height, stroke=True, fill=True)
            current_y += height
    rotated_category_labels(canvas, label_positions, area.bottom - 28)
    canvas.text(area.left + area.width / 2.0, 52, "Group", size=11, align="center")
    draw_legend(
        canvas,
        618,
        392,
        [(label.replace("_", " "), OUTCOME_COLORS[label], True) for label in categories],
        title="Outcome",
    )
    canvas.save(output_path)
    return output_path


def compute_amount_bins(records: list[InvoiceRecord], amount_bins: int) -> list[tuple[float, float]]:
    minimum = min(record.amt for record in records)
    maximum = max(record.amt for record in records)
    if amount_bins <= 1 or minimum == maximum:
        return [(minimum, maximum)]
    width = (maximum - minimum) / float(amount_bins)
    bins = []
    start = float(minimum)
    for index in range(amount_bins):
        end = float(maximum) if index == amount_bins - 1 else start + width
        bins.append((start, end))
        start = end
    return bins


def write_success_rate_curve(
    groups: list[Group],
    all_records: list[InvoiceRecord],
    output_dir: Path,
    title_prefix: str,
    amount_bins: int,
) -> Path:
    output_path = output_dir / "success_rate_by_amount_bin.pdf"
    canvas = PdfCanvas()
    bins = compute_amount_bins(all_records, amount_bins)
    x_values = [0.5 * (start + end) for start, end in bins]
    area = PlotArea(88, 108, 500, 310, min(x_values), max(x_values), 0.0, 1.0)
    x_ticks = [(x, humanize_number(x)) for x in x_values]
    y_ticks = [(value, format_percent(value)) for value in [0.0, 0.25, 0.5, 0.75, 1.0]]
    draw_numeric_axes(
        canvas,
        area,
        f"{title_prefix}: Success Rate by Amount",
        "Invoice amount bin midpoint [sat]",
        "Success rate",
        x_ticks,
        y_ticks,
    )
    legend_items: list[tuple[str, tuple[float, float, float], bool]] = []
    for group in groups:
        points: list[tuple[float, float]] = []
        for start, end in bins:
            if start == end:
                bucket = [record for record in group.records if record.amt == start]
            else:
                bucket = [
                    record
                    for record in group.records
                    if start <= record.amt < end or (end == bins[-1][1] and record.amt <= end)
                ]
            if not bucket:
                continue
            midpoint = 0.5 * (start + end)
            rate = sum(1 for record in bucket if record.success) / len(bucket)
            points.append((area.map_x(midpoint), area.map_y(rate)))
        if len(points) >= 2:
            canvas.set_stroke_color(group.color)
            canvas.set_line_width(1.8)
            canvas.polyline(points)
        canvas.set_stroke_color(group.color)
        canvas.set_fill_color(group.color)
        for x, y in points:
            canvas.circle(x, y, 3.1, stroke=False, fill=True)
        legend_items.append((group.label, group.color, True))
    draw_legend(canvas, 618, 392, legend_items, title="Group")
    canvas.save(output_path)
    return output_path


def write_boxplot(
    groups: list[Group],
    output_dir: Path,
    filename: str,
    title: str,
    y_label: str,
    series_getter,
) -> Path:
    output_path = output_dir / filename
    all_values = [value for group in groups for value in series_getter(group)]
    y_max = max(all_values) if all_values else 1.0
    area = PlotArea(88, 108, 500, 310, 0, max(1, len(groups)), 0.0, max(1.0, y_max))
    y_ticks = [(value, humanize_number(value)) for value in nice_ticks(area.y_min, area.y_max)]
    canvas = PdfCanvas()
    draw_categorical_y_axis(canvas, area, title, y_label, y_ticks)
    box_width = area.width / max(1, len(groups)) * 0.46
    label_positions: list[tuple[float, str]] = []
    for index, group in enumerate(groups):
        values = series_getter(group)
        stats = boxplot_stats(values)
        x_center = area.left + (index + 0.5) / len(groups) * area.width
        label_positions.append((x_center, group.label))
        x_left = x_center - box_width / 2.0
        q1 = area.map_y(float(stats["q1"]))
        median = area.map_y(float(stats["median"]))
        q3 = area.map_y(float(stats["q3"]))
        whisker_low = area.map_y(float(stats["whisker_low"]))
        whisker_high = area.map_y(float(stats["whisker_high"]))
        canvas.set_fill_color(group.color)
        canvas.set_stroke_color((0.18, 0.18, 0.18))
        canvas.set_line_width(1.0)
        canvas.rect(x_left, q1, box_width, max(1.0, q3 - q1), stroke=True, fill=True)
        canvas.set_stroke_color((1.0, 1.0, 1.0))
        canvas.line(x_left, median, x_left + box_width, median)
        canvas.set_stroke_color((0.18, 0.18, 0.18))
        canvas.line(x_center, q3, x_center, whisker_high)
        canvas.line(x_center, q1, x_center, whisker_low)
        cap_half = box_width * 0.28
        canvas.line(x_center - cap_half, whisker_high, x_center + cap_half, whisker_high)
        canvas.line(x_center - cap_half, whisker_low, x_center + cap_half, whisker_low)
        canvas.set_fill_color((1.0, 1.0, 1.0))
        for outlier in stats["outliers"]:
            canvas.circle(x_center, area.map_y(float(outlier)), 2.0, stroke=True, fill=False)
    rotated_category_labels(canvas, label_positions, area.bottom - 28)
    canvas.text(area.left + area.width / 2.0, 52, "Group", size=11, align="center")
    canvas.save(output_path)
    return output_path


def write_filter_breakdown(groups: list[Group], output_dir: Path, title_prefix: str) -> Path:
    output_path = output_dir / "returned_path_filter_breakdown.pdf"
    canvas = PdfCanvas()
    area = PlotArea(88, 108, 500, 310, 0, max(1, len(groups)), 0.0, 1.0)
    draw_categorical_y_axis(
        canvas,
        area,
        f"{title_prefix}: Returned Path Fate",
        "Share of returned paths",
        [(value, format_percent(value)) for value in [0.0, 0.25, 0.5, 0.75, 1.0]],
    )
    categories = [
        "filtered_policy",
        "filtered_capacity",
        "filtered_local_liquidity",
        "filtered_max_fees",
        "kept_as_candidate",
    ]
    bar_width = area.width / max(1, len(groups)) * 0.58
    label_positions: list[tuple[float, str]] = []
    for index, group in enumerate(groups):
        totals = {
            "filtered_policy": sum(record.filtered_policy for record in group.records),
            "filtered_capacity": sum(record.filtered_capacity for record in group.records),
            "filtered_local_liquidity": sum(record.filtered_local_liquidity for record in group.records),
            "filtered_max_fees": sum(record.filtered_max_fees for record in group.records),
            "kept_as_candidate": sum(record.candidate_paths for record in group.records),
        }
        denominator = max(1, sum(record.search_returned_paths for record in group.records))
        x_center = area.left + (index + 0.5) / len(groups) * area.width
        label_positions.append((x_center, group.label))
        x_left = x_center - bar_width / 2.0
        current_y = area.bottom
        for category in categories:
            height = area.height * (totals[category] / denominator)
            if height <= 0:
                continue
            canvas.set_stroke_color((1.0, 1.0, 1.0))
            canvas.set_fill_color(FILTER_COLORS[category])
            canvas.rect(x_left, current_y, bar_width, height, stroke=True, fill=True)
            current_y += height
    rotated_category_labels(canvas, label_positions, area.bottom - 28)
    canvas.text(area.left + area.width / 2.0, 52, "Group", size=11, align="center")
    draw_legend(
        canvas,
        618,
        392,
        [
            ("filtered policy", FILTER_COLORS["filtered_policy"], True),
            ("filtered capacity", FILTER_COLORS["filtered_capacity"], True),
            ("filtered local liquidity", FILTER_COLORS["filtered_local_liquidity"], True),
            ("filtered max fees", FILTER_COLORS["filtered_max_fees"], True),
            ("kept as candidate", FILTER_COLORS["kept_as_candidate"], True),
        ],
        title="Returned path fate",
    )
    canvas.save(output_path)
    return output_path


def write_attempt_breakdown(groups: list[Group], output_dir: Path, title_prefix: str) -> Path:
    output_path = output_dir / "attempt_result_breakdown.pdf"
    canvas = PdfCanvas()
    area = PlotArea(88, 108, 500, 310, 0, max(1, len(groups)), 0.0, 1.0)
    draw_categorical_y_axis(
        canvas,
        area,
        f"{title_prefix}: Attempted Path Outcomes",
        "Share of attempted paths",
        [(value, format_percent(value)) for value in [0.0, 0.25, 0.5, 0.75, 1.0]],
    )
    categories = [
        "success_path",
        "temporary_channel_failure",
        "expiry_too_soon",
        "local_liquidity_failure",
        "timeout",
        "unknown",
    ]
    bar_width = area.width / max(1, len(groups)) * 0.58
    label_positions: list[tuple[float, str]] = []
    for index, group in enumerate(groups):
        totals = {
            "success_path": sum(1 for record in group.records if record.success),
            "temporary_channel_failure": sum(record.attempt_failed_temporary_channel for record in group.records),
            "expiry_too_soon": sum(record.attempt_failed_expiry_too_soon for record in group.records),
            "local_liquidity_failure": sum(record.attempt_failed_local_liquidity for record in group.records),
            "timeout": sum(record.attempt_failed_timeout for record in group.records),
            "unknown": sum(record.attempt_failed_unknown for record in group.records),
        }
        denominator = max(1, sum(record.attempted_paths for record in group.records))
        x_center = area.left + (index + 0.5) / len(groups) * area.width
        label_positions.append((x_center, group.label))
        x_left = x_center - bar_width / 2.0
        current_y = area.bottom
        for category in categories:
            height = area.height * (totals[category] / denominator)
            if height <= 0:
                continue
            canvas.set_stroke_color((1.0, 1.0, 1.0))
            canvas.set_fill_color(ATTEMPT_COLORS[category])
            canvas.rect(x_left, current_y, bar_width, height, stroke=True, fill=True)
            current_y += height
    rotated_category_labels(canvas, label_positions, area.bottom - 28)
    canvas.text(area.left + area.width / 2.0, 52, "Group", size=11, align="center")
    draw_legend(
        canvas,
        618,
        392,
        [
            ("successful path", ATTEMPT_COLORS["success_path"], True),
            ("temporary channel", ATTEMPT_COLORS["temporary_channel_failure"], True),
            ("expiry too soon", ATTEMPT_COLORS["expiry_too_soon"], True),
            ("local liquidity", ATTEMPT_COLORS["local_liquidity_failure"], True),
            ("timeout", ATTEMPT_COLORS["timeout"], True),
            ("unknown", ATTEMPT_COLORS["unknown"], True),
        ],
        title="Attempt outcome",
    )
    canvas.save(output_path)
    return output_path


def write_search_exclusion_mix(groups: list[Group], output_dir: Path, title_prefix: str) -> Path:
    output_path = output_dir / "search_exclusion_mix.pdf"
    canvas = PdfCanvas()
    area = PlotArea(88, 108, 500, 310, 0, max(1, len(groups)), 0.0, 1.0)
    draw_categorical_y_axis(
        canvas,
        area,
        f"{title_prefix}: Search Exclusion Mix",
        "Share of exclusions",
        [(value, format_percent(value)) for value in [0.0, 0.25, 0.5, 0.75, 1.0]],
    )
    categories = ["capacity", "visited_state", "cycle", "max_hops", "cost"]
    bar_width = area.width / max(1, len(groups)) * 0.58
    label_positions: list[tuple[float, str]] = []
    for index, group in enumerate(groups):
        totals = {
            "capacity": sum(record.search_excluded_capacity for record in group.records),
            "visited_state": sum(record.search_excluded_visited_state for record in group.records),
            "cycle": sum(record.search_excluded_cycle for record in group.records),
            "max_hops": sum(record.search_excluded_max_hops for record in group.records),
            "cost": sum(record.search_excluded_cost for record in group.records),
        }
        denominator = max(1, sum(totals.values()))
        x_center = area.left + (index + 0.5) / len(groups) * area.width
        label_positions.append((x_center, group.label))
        x_left = x_center - bar_width / 2.0
        current_y = area.bottom
        for category in categories:
            height = area.height * (totals[category] / denominator)
            if height <= 0:
                continue
            canvas.set_stroke_color((1.0, 1.0, 1.0))
            canvas.set_fill_color(SEARCH_MIX_COLORS[category])
            canvas.rect(x_left, current_y, bar_width, height, stroke=True, fill=True)
            current_y += height
    rotated_category_labels(canvas, label_positions, area.bottom - 28)
    canvas.text(area.left + area.width / 2.0, 52, "Group", size=11, align="center")
    draw_legend(
        canvas,
        618,
        392,
        [
            ("capacity", SEARCH_MIX_COLORS["capacity"], True),
            ("visited state", SEARCH_MIX_COLORS["visited_state"], True),
            ("cycle", SEARCH_MIX_COLORS["cycle"], True),
            ("max hops", SEARCH_MIX_COLORS["max_hops"], True),
            ("invalid cost", SEARCH_MIX_COLORS["cost"], True),
        ],
        title="Search pruning",
    )
    canvas.save(output_path)
    return output_path


def write_scatter(groups: list[Group], output_dir: Path, title_prefix: str, scatter_limit: int) -> Path:
    output_path = output_dir / "amount_vs_search_expanded_edges.pdf"
    points = [record for group in groups for record in group.records]
    x_min = min(record.amt for record in points)
    x_max = max(record.amt for record in points)
    y_values = [math.log10(record.search_expanded_edges + 1.0) for record in points]
    area = PlotArea(88, 108, 500, 310, x_min, x_max, min(y_values), max(y_values))
    x_ticks = [(tick, humanize_number(tick)) for tick in nice_ticks(area.x_min, area.x_max)]
    y_ticks = [(tick, f"{tick:.1f}") for tick in nice_ticks(area.y_min, area.y_max)]
    canvas = PdfCanvas()
    draw_numeric_axes(
        canvas,
        area,
        f"{title_prefix}: Search Complexity vs Amount",
        "Invoice amount [sat]",
        "log10(search_expanded_edges + 1)",
        x_ticks,
        y_ticks,
    )
    group_items: list[tuple[str, tuple[float, float, float], bool]] = []
    for group in groups:
        sample = evenly_downsample(sorted(group.records, key=lambda record: (record.amt, record.hash)), scatter_limit)
        for record in sample:
            x = area.map_x(record.amt)
            y = area.map_y(math.log10(record.search_expanded_edges + 1.0))
            canvas.set_stroke_color(group.color)
            if record.success:
                canvas.set_fill_color(group.color)
                canvas.circle(x, y, 2.8, stroke=False, fill=True)
            else:
                canvas.set_fill_color((1.0, 1.0, 1.0))
                canvas.circle(x, y, 2.8, stroke=True, fill=False)
        group_items.append((group.label, group.color, True))
    draw_legend(canvas, 618, 392, group_items, title="Group")
    draw_legend(
        canvas,
        618,
        272,
        [
            ("success", (0.20, 0.20, 0.20), True),
            ("failure", (0.20, 0.20, 0.20), False),
        ],
        title="Marker style",
    )
    canvas.save(output_path)
    return output_path


def write_summary(groups: list[Group], output_dir: Path) -> Path:
    output_path = output_dir / "figure_summary.txt"
    lines = ["Invoice report plotting summary", ""]
    for group in groups:
        success_rate = sum(1 for record in group.records if record.success) / max(1, len(group.records))
        median_candidate_paths = quantile([record.candidate_paths for record in group.records], 0.50)
        median_expanded_edges = quantile([record.search_expanded_edges for record in group.records], 0.50)
        lines.extend(
            [
                f"[{group.label}]",
                f"  invoices={len(group.records)}",
                f"  success_rate={success_rate:.3f}",
                f"  median_candidate_paths={median_candidate_paths:.1f}",
                f"  median_search_expanded_edges={median_expanded_edges:.1f}",
                "",
            ]
        )
    output_path.write_text("\n".join(lines), encoding="utf-8")
    return output_path


def main() -> int:
    args = parse_args()
    report_paths = [Path(path) for path in args.reports]
    records = load_records(report_paths)
    groups = build_groups(records)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    generated_paths = [
        write_invoice_outcomes(groups, output_dir, args.title_prefix),
        write_success_rate_curve(groups, records, output_dir, args.title_prefix, args.amount_bins),
        write_boxplot(
            groups,
            output_dir,
            "search_expanded_edges_boxplot_log10.pdf",
            f"{args.title_prefix}: Search Expanded Edges",
            "log10(search_expanded_edges + 1)",
            lambda group: [math.log10(record.search_expanded_edges + 1.0) for record in group.records],
        ),
        write_boxplot(
            groups,
            output_dir,
            "candidate_paths_boxplot.pdf",
            f"{args.title_prefix}: Candidate Paths",
            "Candidate paths per invoice",
            lambda group: [float(record.candidate_paths) for record in group.records],
        ),
        write_filter_breakdown(groups, output_dir, args.title_prefix),
        write_attempt_breakdown(groups, output_dir, args.title_prefix),
        write_search_exclusion_mix(groups, output_dir, args.title_prefix),
        write_scatter(groups, output_dir, args.title_prefix, args.scatter_limit),
        write_summary(groups, output_dir),
    ]

    print("Generated files:")
    for path in generated_paths:
        print(f" - {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
