#!/usr/bin/env bash
# List running Docker containers; pass options such as -a to show stopped ones too.
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is not installed or is not on PATH." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Cannot reach the Docker daemon. Start Docker Desktop and wait until it is ready, then rerun this script." >&2
  if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "Start Docker Desktop with: open -a Docker" >&2
  fi
  exit 1
fi

exec docker ps "$@"
