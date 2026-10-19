#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

status=0

declare -a blocked_exact=(
  "CLAUDE.md"
  "AGENTS.md"
  "docs/agent_handoff.md"
)

for path in "${blocked_exact[@]}"; do
  if git ls-files --error-unmatch "$path" >/dev/null 2>&1 && [[ -e "$path" ]]; then
    echo "Tracked private/development file detected: $path" >&2
    status=1
  fi
done

path_pattern='(^build-local/|(^|/)\.codex/|(^|/)\.claude/|(^|/)docs/\.claude/|(^|/)\.private/|(^|/)\.envrc$|(^|/)\.direnv/|(^|/).+\.code-workspace$|(^|/)local\.properties$|(^|/)signing\.properties$|(^|/).+\.(keystore|jks)$|(^|/)docs/session-notes-[0-9-]+\.md$)'
path_hits="$(
  git ls-files | grep -E "$path_pattern" || true
)"
if [[ -n "$path_hits" ]]; then
  echo "Tracked files that should stay out of the public repo:" >&2
  echo "$path_hits" >&2
  status=1
fi

content_hits="$(
  (git grep -nIE '(agent handoff|project memory anchor|VS Code sessions|/home/[^/[:space:]]+|/Users/[^/[:space:]]+|tinyurl\.com)' -- . ':(exclude)scripts/check-public-surface.sh' || true) |
    python3 -c '
import re
import sys

# Only these intentional redaction inputs are synthetic; inspect the rest of
# each matching line normally, including other home paths and private markers.
allowed = {
    "app/src/test/java/com/papi/nova/diagnostics/NovaMomentLogTest.kt": r"/(?:home|Users)/redaction-fixture/",
    "clients/deck/tests/deck_moment_report_test.cpp": r"/home/private(?=[\"\x27\s])",
    "clients/deck/tests/deck_working_baseline_test.cpp": r"/home/private(?=[\"\x27\s])",
}
private = re.compile(r"agent handoff|project memory anchor|VS Code sessions|/home/[^/\s]+|/Users/[^/\s]+|tinyurl\.com")
for line in sys.stdin:
    path, _, body = line.rstrip("\n").split(":", 2)
    if path in allowed:
        body = re.sub(allowed[path], "", body)
    if private.search(body):
        print(line, end="")
'
)"
if [[ -n "$content_hits" ]]; then
  echo "Suspicious private or maintainer-only strings detected in tracked files:" >&2
  echo "$content_hits" >&2
  status=1
fi

if [[ "$status" -ne 0 ]]; then
  echo "Public repo surface check failed." >&2
  exit "$status"
fi

echo "Public repo surface looks clean."
