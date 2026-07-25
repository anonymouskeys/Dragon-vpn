#!/usr/bin/env bash
set -Eeuo pipefail

WORKFLOW="android-universal.yml"
GRADLE_FILE="V2rayNG/app/build.gradle.kts"
REMOTE="${RELEASE_REMOTE:-origin}"

fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '\n==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"; }
usage() {
  cat <<'USAGE'
Usage:
  ./release.sh VERSION [RELEASE_NOTES_FILE]

Examples:
  ./release.sh 2.2.7
  ./release.sh v2.2.7 CHANGELOG.md
USAGE
}

[ $# -ge 1 ] && [ $# -le 2 ] || { usage; exit 2; }
need git
need gh
need python3

VERSION="${1#v}"
NOTES_FILE="${2:-}"
TAG="v$VERSION"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
  || fail "Version must look like 2.2.7 (optionally with a suffix)."

[ -f "$GRADLE_FILE" ] || fail "Run this command from the Monster-VPN repository root."
[ -z "$NOTES_FILE" ] || [ -f "$NOTES_FILE" ] || fail "Release notes file not found: $NOTES_FILE"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Not inside a Git repository."
[ -z "$(git status --porcelain)" ] || fail "Working tree is not clean. Commit or stash changes first."
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authorized. Run: gh auth login"
git remote get-url "$REMOTE" >/dev/null 2>&1 || fail "Git remote '$REMOTE' does not exist."

BRANCH="$(git symbolic-ref --quiet --short HEAD)" || fail "Detached HEAD is not supported."
UPSTREAM="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
[ -n "$UPSTREAM" ] || fail "Branch '$BRANCH' has no upstream. Push it once with: git push -u $REMOTE $BRANCH"

git fetch "$REMOTE" --tags || true
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}')" ] \
  || fail "Local branch is not synchronized with $UPSTREAM. Pull or push first."
if git show-ref --verify --quiet "refs/tags/$TAG" || git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then
  fail "Tag already exists: $TAG"
fi

read -r CURRENT_NAME CURRENT_CODE < <(python3 - "$GRADLE_FILE" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8")
name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
code = re.search(r'^\s*versionCode\s*=\s*(\d+)', text, re.M)
if not name or not code:
    raise SystemExit("Cannot find versionName/versionCode")
print(name.group(1), code.group(1))
PY
)
[ "$CURRENT_NAME" != "$VERSION" ] || fail "versionName is already $VERSION"
NEW_CODE=$((CURRENT_CODE + 1))

printf 'Current release: %s (code %s)\n' "$CURRENT_NAME" "$CURRENT_CODE"
printf 'New release:     %s (code %s)\n' "$VERSION" "$NEW_CODE"
read -r -p "Publish $TAG from branch $BRANCH? [y/N]: " CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || fail "Release cancelled."

python3 - "$GRADLE_FILE" "$VERSION" "$NEW_CODE" <<'PY'
import re, sys
from pathlib import Path
path = Path(sys.argv[1]); version = sys.argv[2]; code = sys.argv[3]
text = path.read_text(encoding="utf-8")
text, n1 = re.subn(r'(^\s*versionCode\s*=\s*)\d+', rf'\g<1>{code}', text, count=1, flags=re.M)
text, n2 = re.subn(r'(^\s*versionName\s*=\s*)"[^"]+"', rf'\g<1>"{version}"', text, count=1, flags=re.M)
if n1 != 1 or n2 != 1:
    raise SystemExit("Version replacement failed")
path.write_text(text, encoding="utf-8")
PY

trap 'status=$?; [ "$status" -eq 0 ] || printf "\nRelease command failed. Local commit/tag may remain for inspection.\n" >&2' EXIT

info "Creating release commit and tag"
git add "$GRADLE_FILE"
git commit -m "Release $TAG"
if [ -n "$NOTES_FILE" ]; then
  git tag -a "$TAG" -F "$NOTES_FILE"
else
  git tag -a "$TAG" -m "Monster VPN $TAG"
fi

info "Pushing commit and tag atomically"
git push --atomic "$REMOTE" "$BRANCH" "$TAG"

info "Starting signed GitHub Actions release build"
gh workflow run "$WORKFLOW" --ref "$TAG"

RUN_ID=""
for _ in $(seq 1 30); do
  RUN_ID="$(gh run list --workflow "$WORKFLOW" --event workflow_dispatch --limit 30 \
    --json databaseId,headBranch \
    --jq ".[] | select(.headBranch == \"$TAG\") | .databaseId" | head -n 1)"
  [ -n "$RUN_ID" ] && break
  sleep 2
done
[ -n "$RUN_ID" ] || fail "Workflow was dispatched, but its run could not be found."

info "Waiting for GitHub Actions run $RUN_ID"
gh run watch "$RUN_ID" --exit-status

info "Release published"
gh release view "$TAG" --json url --jq '.url'
trap - EXIT
