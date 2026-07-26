#!/usr/bin/env bash
set -Eeuo pipefail

# Backward-compatible entry point.
exec "$(dirname "$0")/scripts/release-assistant.sh" "$@"
