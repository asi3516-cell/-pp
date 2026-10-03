#!/bin/bash
# macOS: double-click this file to start the player.
cd "$(dirname "$0")" || exit 1
PORT="${PORT:-8000}"
exec python3 server.py --port "$PORT" --open
