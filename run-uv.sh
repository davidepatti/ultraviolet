#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

BUILD_DIR="$SCRIPT_DIR/out/production/ultraviolet"
DEFAULT_CONFIG="$SCRIPT_DIR/uv_configs/template.properties"
JSON_JAR="$SCRIPT_DIR/src/json-simple-1.1.jar"

if [ ! -f "$JSON_JAR" ]; then
  echo "json-simple jar not found: $JSON_JAR" >&2
  exit 1
fi

CONFIG_PATH="${1:-$DEFAULT_CONFIG}"
if [ $# -gt 0 ]; then
  shift
fi

if [ ! -f "$CONFIG_PATH" ]; then
  echo "Config file not found: $CONFIG_PATH" >&2
  exit 1
fi

mkdir -p "$BUILD_DIR"
find "$BUILD_DIR" -name '*.class' -delete

TMP_SOURCES=$(mktemp)
trap 'rm -f "$TMP_SOURCES"' EXIT INT TERM
find "$SCRIPT_DIR/src" -name '*.java' | sort > "$TMP_SOURCES"

javac -cp "$JSON_JAR" -d "$BUILD_DIR" @"$TMP_SOURCES"

exec java -cp "$BUILD_DIR:$JSON_JAR" UltraViolet "$CONFIG_PATH"
