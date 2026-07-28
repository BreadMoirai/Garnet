#!/usr/bin/env bash
# Reindex the qmd docs collection (see docs/tooling/docs-search.md).
#
# Wired to two events in .claude/settings.json:
#   PostToolUse(Write|Edit) — payload on stdin carries .tool_input.file_path
#   SessionStart            — payload carries no file path; catches out-of-band edits
#                             (git pull, rebase, IDE) the write hook cannot see
#
# Always exits 0. A failure here must never interrupt a turn.
set -u

REPO="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# Hooks run non-interactively, so ~/.zshrc is never sourced and qmd's install dir is
# absent from PATH. Add the likely homes before probing for the binary. Node's bin is
# appended last so it lands FIRST in PATH: qmd is installed there, and a stray qmd from
# another package manager must not win — a shadowing install with an unbuilt native
# sqlite addon fails at runtime rather than being skipped cleanly.
for d in "$HOME/.bun/bin" "$HOME"/.local/lib/node-*/bin; do
  [ -d "$d" ] && PATH="$d:$PATH"
done
export PATH

# Not installed → no-op, so the repo stays usable without qmd.
command -v qmd >/dev/null 2>&1 || exit 0

# Read the payload if one is piped in. python3, not jq: jq is not installed here.
payload=""
if [ ! -t 0 ]; then
  payload="$(cat)"
fi

path=""
if [ -n "$payload" ]; then
  path="$(printf '%s' "$payload" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
ti = d.get("tool_input") or {}
print(ti.get("file_path") or "")
' 2>/dev/null)"

  # A path present but outside docs/*.md means this was a source edit — skip.
  # No path at all (SessionStart) falls through and reindexes.
  if [ -n "$path" ]; then
    case "$path" in
      "$REPO"/docs/*.md|docs/*.md) ;;
      *) exit 0 ;;
    esac
  fi
fi

mkdir -p "$REPO/.qmd"

# Serialize. Rapid successive doc writes would otherwise race on the SQLite DB.
# Non-blocking: a run arriving while another holds the lock skips, because the
# in-flight run rescans by mtime and picks the newer file up anyway.
exec 9>"$REPO/.qmd/reindex.lock"
flock -n 9 || exit 0

cd "$REPO" || exit 0
{
  echo "=== $(date -Is) reindex (trigger: ${path:-session-start}) ==="
  qmd update && qmd embed
} >>"$REPO/.qmd/reindex.log" 2>&1

exit 0
