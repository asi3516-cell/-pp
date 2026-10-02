#!/usr/bin/env bash
# Linux / macOS launcher.
cd "$(dirname "$0")" || exit 1
exec python3 server.py --port "${PORT:-8000}" --open
