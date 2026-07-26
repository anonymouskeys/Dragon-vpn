#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_REPOSITORY="anonymouskeys/Dragon-vpn"
WORKFLOW="android-universal.yml"
GRADLE_FILE="V2rayNG/app/build.gradle.kts"
REMOTE="${RELEASE_REMOTE:-origin}"
DOWNLOAD_ROOT="${RELEASE_DOWNLOAD_DIR:-$PWD/releases}"

if [ -t 1 ]; then
  BOLD='\033[1m'; DIM='\033[2m'; GREEN='\033[32m'; YELLOW='\033[33m'; RED='\033[31m'; CYAN='\033[36m'; RESET='\033[0m'
else
  BOLD=''; DIM=''; GREEN=''; YELLOW=''; RED=''; CYAN=''; RESET=''
fi

say()  { printf '%b\n' "$*"; }
step() { printf '\n%b🐉 %s%b\n' "$CYAN$BOLD" "$*" "$RESET"; }
ok()   { printf '%b✓ %s%b\n' "$GREEN" "$*" "$RESET"; }
warn() { printf '%b! %s%b\n' "$YELLOW" "$*" "$RESET" >&2; }
fail() { printf '\n%b✗ %s%b\n' "$RED$BOLD" "$*" "$RESET" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "Не найдена команда: $1"; }
confirm() {
  local answer
  read -r -p "$1 [y/N]: " answer
  [[ "$answer" =~ ^[Yy]$ ]]
}
usage() {
  cat <<'USAGE'
DragonVPN Release Assistant

Использование:
  ./scripts/release-assistant.sh
  ./scripts/release-assistant.sh VERSION [RELEASE_NOTES_FILE]

Примеры:
  ./scripts/release-assistant.sh 2.2.9
  ./scripts/release-assistant.sh v2.2.9 CHANGELOG.md

Переменные:
  RELEASE_REMOTE=origin
  RELEASE_DOWNLOAD_DIR=/storage/emulated/0/Download/DragonVPN-Releases
USAGE
}

[ "${1:-}" != "--help" ] || { usage; exit 0; }
[ $# -le 2 ] || { usage; exit 2; }

need git
need gh
need python3
need sha256sum

[ -f "$GRADLE_FILE" ] || fail "Запусти помощника из корня репозитория DragonVPN."
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Текущая папка не является Git-репозиторием."
gh auth status >/dev/null 2>&1 || fail "GitHub CLI не авторизован. Выполни: gh auth login"

step "Проверка репозитория"
REMOTE_URL="$(git remote get-url "$REMOTE" 2>/dev/null || true)"
[ -n "$REMOTE_URL" ] || fail "Не найден git remote '$REMOTE'."
printf '%s\n' "Remote: $REMOTE_URL"
case "$REMOTE_URL" in
  https://github.com/${EXPECTED_REPOSITORY}.git|git@github.com:${EXPECTED_REPOSITORY}.git|ssh://git@github.com/${EXPECTED_REPOSITORY}.git) ;;
  *) fail "Остановлено: remote должен указывать на https://github.com/${EXPECTED_REPOSITORY}.git" ;;
esac
GH_REPOSITORY="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
[ "$GH_REPOSITORY" = "$EXPECTED_REPOSITORY" ] || fail "gh открыт для '$GH_REPOSITORY', ожидался '$EXPECTED_REPOSITORY'."
ok "Репозиторий подтверждён: $GH_REPOSITORY"

step "Проверка рабочей копии"
[ -z "$(git status --porcelain)" ] || fail "Есть незакоммиченные изменения. Сначала закоммить или убери их."
BRANCH="$(git symbolic-ref --quiet --short HEAD)" || fail "Detached HEAD не поддерживается."
UPSTREAM="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
[ -n "$UPSTREAM" ] || fail "У ветки '$BRANCH' нет upstream. Выполни: git push -u $REMOTE $BRANCH"
git fetch "$REMOTE" --tags --prune
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}')" ] || fail "Локальная ветка не синхронизирована с $UPSTREAM."
ok "Ветка $BRANCH чистая и синхронизирована"

step "Проверка GitHub Actions и секретов"
gh workflow view "$WORKFLOW" >/dev/null || fail "Workflow '$WORKFLOW' не найден или отключён."
REQUIRED_SECRETS=(APP_KEYSTORE_BASE64 APP_KEYSTORE_PASSWORD APP_KEYSTORE_ALIAS APP_KEY_PASSWORD)
SECRET_NAMES="$(gh secret list --json name --jq '.[].name')"
for secret in "${REQUIRED_SECRETS[@]}"; do
  grep -Fxq "$secret" <<<"$SECRET_NAMES" || fail "В GitHub отсутствует секрет: $secret"
done
ok "Все секреты подписи существуют; их значения не читались и не изменялись"

read -r CURRENT_NAME CURRENT_CODE < <(python3 - "$GRADLE_FILE" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8")
name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
code = re.search(r'^\s*versionCode\s*=\s*(\d+)', text, re.M)
if not name or not code:
    raise SystemExit("Не удалось найти versionName/versionCode")
print(name.group(1), code.group(1))
PY
)

VERSION="${1:-}"
NOTES_FILE="${2:-}"
if [ -z "$VERSION" ]; then
  say ""
  say "Текущая версия: ${BOLD}${CURRENT_NAME}${RESET} (code ${CURRENT_CODE})"
  read -r -p "Новая версия (например 2.2.9): " VERSION
fi
VERSION="${VERSION#v}"
TAG="v$VERSION"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || fail "Версия должна выглядеть как 2.2.9."
[ "$CURRENT_NAME" != "$VERSION" ] || fail "versionName уже равен $VERSION."
[ -z "$NOTES_FILE" ] || [ -f "$NOTES_FILE" ] || fail "Файл заметок не найден: $NOTES_FILE"
if git show-ref --verify --quiet "refs/tags/$TAG" || git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then
  fail "Тег уже существует: $TAG"
fi
NEW_CODE=$((CURRENT_CODE + 1))

step "План релиза"
printf '  Репозиторий: %s\n' "$EXPECTED_REPOSITORY"
printf '  Ветка:       %s\n' "$BRANCH"
printf '  Версия:      %s → %s\n' "$CURRENT_NAME" "$VERSION"
printf '  VersionCode: %s → %s\n' "$CURRENT_CODE" "$NEW_CODE"
printf '  Тег:         %s\n' "$TAG"
printf '  Подпись:     существующие GitHub Secrets (без изменения)\n'
confirm "Создать и опубликовать релиз $TAG?" || fail "Релиз отменён."

python3 - "$GRADLE_FILE" "$VERSION" "$NEW_CODE" <<'PY'
import re, sys
from pathlib import Path
path = Path(sys.argv[1]); version = sys.argv[2]; code = sys.argv[3]
text = path.read_text(encoding="utf-8")
text, n1 = re.subn(r'(^\s*versionCode\s*=\s*)\d+', rf'\g<1>{code}', text, count=1, flags=re.M)
text, n2 = re.subn(r'(^\s*versionName\s*=\s*)"[^"]+"', rf'\g<1>"{version}"', text, count=1, flags=re.M)
if n1 != 1 or n2 != 1:
    raise SystemExit("Не удалось обновить версию")
path.write_text(text, encoding="utf-8")
PY

rollback_hint() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    warn "Процесс остановился. Коммит или тег могли остаться локально для проверки."
    warn "Ничего не удалено автоматически. Проверь: git status; git log -1; git tag --list '$TAG'"
  fi
  exit "$status"
}
trap rollback_hint EXIT

step "Создание release-коммита и тега"
git add "$GRADLE_FILE"
git commit -m "Release $TAG"
if [ -n "$NOTES_FILE" ]; then
  git tag -a "$TAG" -F "$NOTES_FILE"
else
  git tag -a "$TAG" -m "DragonVPN $TAG"
fi
ok "Созданы коммит и тег $TAG"

step "Отправка коммита и тега"
printf '%s\n' "Перед push подтверждён remote: $REMOTE_URL"
git push --atomic "$REMOTE" "$BRANCH" "$TAG"
ok "Push выполнен атомарно"

step "Запуск подписанной сборки GitHub Actions"
gh workflow run "$WORKFLOW" --ref "$TAG"
RUN_ID=""
for _ in $(seq 1 45); do
  RUN_ID="$(gh run list --workflow "$WORKFLOW" --event workflow_dispatch --limit 50 \
    --json databaseId,headBranch,headSha \
    --jq ".[] | select(.headBranch == \"$TAG\") | .databaseId" | head -n 1)"
  [ -n "$RUN_ID" ] && break
  sleep 2
done
[ -n "$RUN_ID" ] || fail "Workflow запущен, но его run не найден."
printf '%s\n' "Run ID: $RUN_ID"
gh run watch "$RUN_ID" --exit-status
ok "GitHub Actions завершился успешно"

step "Скачивание и проверка артефактов"
DEST="$DOWNLOAD_ROOT/$TAG"
mkdir -p "$DEST"
gh run download "$RUN_ID" --dir "$DEST"
APK="$(find "$DEST" -type f -name '*.apk' | head -n 1)"
[ -n "$APK" ] && [ -f "$APK" ] || fail "APK не найден в скачанном артефакте: $DEST"
LOCAL_SHA="$(sha256sum "$APK" | tee "$APK.sha256.local" | awk '{print $1}')"
PUBLISHED_SHA_FILE="$(find "$DEST" -type f -name '*.sha256' ! -name '*.local' | head -n 1 || true)"
if [ -n "$PUBLISHED_SHA_FILE" ]; then
  EXPECTED_SHA="$(awk 'NR==1 {print $1}' "$PUBLISHED_SHA_FILE")"
  [ "$LOCAL_SHA" = "$EXPECTED_SHA" ] || fail "SHA-256 APK не совпал с отчётом workflow."
  ok "SHA-256 подтверждён: $LOCAL_SHA"
else
  warn "Файл SHA-256 из workflow не найден; локальный SHA-256: $LOCAL_SHA"
fi

if command -v apksigner >/dev/null 2>&1; then
  apksigner verify --verbose --print-certs "$APK" | tee "$DEST/apksigner-local.txt"
  ok "Локальная проверка подписи пройдена"
else
  warn "apksigner не найден на телефоне. Подпись уже проверена внутри GitHub Actions."
fi

step "Проверка опубликованного GitHub Release"
RELEASE_URL="$(gh release view "$TAG" --json url --jq .url)"
[ -n "$RELEASE_URL" ] || fail "GitHub Release не найден после успешной сборки."
printf '\n%bГотово!%b\n' "$GREEN$BOLD" "$RESET"
printf 'APK:     %s\n' "$APK"
printf 'SHA-256: %s\n' "$LOCAL_SHA"
printf 'Release: %s\n' "$RELEASE_URL"
trap - EXIT
