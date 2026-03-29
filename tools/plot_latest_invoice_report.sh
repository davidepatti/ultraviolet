#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

LATEST_REPORT=$(find "$ROOT_DIR" -maxdepth 1 -type f -name '*_invoice.*.csv' | sort | tail -n 1)

if [ -z "$LATEST_REPORT" ]; then
  echo "No invoice report CSV found in $ROOT_DIR" >&2
  exit 1
fi

REPORT_NAME=$(basename "$LATEST_REPORT")
REPORT_STEM=${REPORT_NAME%.csv}
OUTPUT_DIR="$ROOT_DIR/figures/$REPORT_STEM"

exec python3 "$SCRIPT_DIR/plot_invoice_report.py" "$LATEST_REPORT" --output-dir "$OUTPUT_DIR" "$@"
