#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR="$SCRIPT_DIR/out/production/ultraviolet"
DEFAULT_CONFIG="$SCRIPT_DIR/uv_configs/test.properties"

if [ ! -d "$BUILD_DIR" ]; then
  echo "Build output not found: $BUILD_DIR" >&2
  echo "Build the project in IntelliJ first." >&2
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

JSON_JAR="$BUILD_DIR/json-simple-1.1.jar"
if [ ! -f "$JSON_JAR" ]; then
  JSON_JAR="$SCRIPT_DIR/src/json-simple-1.1.jar"
fi

if [ ! -f "$JSON_JAR" ]; then
  echo "json-simple jar not found in build output or src/." >&2
  exit 1
fi

exec java -cp "$BUILD_DIR:$JSON_JAR" UltraViolet "$CONFIG_PATH" "$@"
