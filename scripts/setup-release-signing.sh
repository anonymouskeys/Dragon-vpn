#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="Monster VPN"
DEFAULT_ALIAS="monster-vpn"
DEFAULT_KEYSTORE="$HOME/monster-vpn-signing/monster-release.jks"
DEFAULT_VALIDITY="10000"

fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '\n==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"; }
read_secret() {
  local prompt="$1" first second
  while :; do
    read -r -s -p "$prompt: " first; printf '\n'
    [ ${#first} -ge 8 ] || { echo "Use at least 8 characters." >&2; continue; }
    read -r -s -p "Repeat $prompt: " second; printf '\n'
    [ "$first" = "$second" ] || { echo "Values do not match." >&2; continue; }
    printf '%s' "$first"
    return
  done
}

need keytool
need base64

printf '%s\n' "Monster VPN release-signing assistant"
printf '%s\n' "This creates the permanent Android update key and can upload GitHub Actions secrets."
printf '%s\n' "Losing this key means existing installations cannot be updated with future APKs."

read -r -p "Keystore path [$DEFAULT_KEYSTORE]: " KEYSTORE_FILE
KEYSTORE_FILE="${KEYSTORE_FILE:-$DEFAULT_KEYSTORE}"
read -r -p "Key alias [$DEFAULT_ALIAS]: " KEY_ALIAS
KEY_ALIAS="${KEY_ALIAS:-$DEFAULT_ALIAS}"
read -r -p "Certificate owner name [Anonymous Keys]: " OWNER
OWNER="${OWNER:-Anonymous Keys}"
read -r -p "Organization [$APP_NAME]: " ORG
ORG="${ORG:-$APP_NAME}"
read -r -p "Country code [RU]: " COUNTRY
COUNTRY="${COUNTRY:-RU}"

if [ -e "$KEYSTORE_FILE" ]; then
  fail "File already exists: $KEYSTORE_FILE. It was not overwritten."
fi

KEYSTORE_PASSWORD="$(read_secret "Keystore password")"
read -r -p "Use the same password for the key? [Y/n]: " SAME_PASSWORD
if [[ "${SAME_PASSWORD:-Y}" =~ ^[Nn]$ ]]; then
  KEY_PASSWORD="$(read_secret "Key password")"
else
  KEY_PASSWORD="$KEYSTORE_PASSWORD"
fi

mkdir -p "$(dirname "$KEYSTORE_FILE")"
umask 077

info "Generating permanent release key"
keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -storetype JKS \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity "$DEFAULT_VALIDITY" \
  -dname "CN=$OWNER, O=$ORG, C=$COUNTRY"

CERT_DIR="$(dirname "$KEYSTORE_FILE")"
CERT_FILE="$CERT_DIR/monster-release-certificate.pem"
FINGERPRINT_FILE="$CERT_DIR/monster-release-fingerprints.txt"

keytool -exportcert -rfc \
  -keystore "$KEYSTORE_FILE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -file "$CERT_FILE" >/dev/null

keytool -list -v \
  -keystore "$KEYSTORE_FILE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS" \
  | grep -E 'SHA1:|SHA256:' > "$FINGERPRINT_FILE"

info "Release certificate fingerprints"
cat "$FINGERPRINT_FILE"

read -r -p "Upload signing values to GitHub Actions now? [Y/n]: " UPLOAD
if [[ ! "${UPLOAD:-Y}" =~ ^[Nn]$ ]]; then
  need gh
  gh auth status >/dev/null 2>&1 || fail "Run 'gh auth login' first."

  if base64 --help 2>&1 | grep -q -- '-w'; then
    KEYSTORE_BASE64="$(base64 -w 0 "$KEYSTORE_FILE")"
  else
    KEYSTORE_BASE64="$(base64 "$KEYSTORE_FILE" | tr -d '\r\n')"
  fi

  info "Uploading encrypted repository secrets"
  printf '%s' "$KEYSTORE_BASE64" | gh secret set APP_KEYSTORE_BASE64
  printf '%s' "$KEYSTORE_PASSWORD" | gh secret set APP_KEYSTORE_PASSWORD
  printf '%s' "$KEY_ALIAS" | gh secret set APP_KEYSTORE_ALIAS
  printf '%s' "$KEY_PASSWORD" | gh secret set APP_KEY_PASSWORD
  echo "GitHub Actions secrets configured."
fi

printf '\n%s\n' "DONE"
printf '%s\n' "Keystore:    $KEYSTORE_FILE"
printf '%s\n' "Certificate: $CERT_FILE"
printf '%s\n' "Fingerprints:$FINGERPRINT_FILE"
printf '\n%s\n' "Make at least two encrypted offline backups of the .jks and passwords."
printf '%s\n' "Never commit the keystore or passwords to Git."
unset KEYSTORE_PASSWORD KEY_PASSWORD KEYSTORE_BASE64 2>/dev/null || true
