#!/usr/bin/env bash
# One-time interactive setup for chainlp-write-proxy. The only things you
# need to know before running this are your real ChainLP's address and
# its real login - everything else (encoding the credential correctly,
# writing .env, starting the container, telling you what to paste into
# CI) is handled here rather than by hand.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "chainlp-write-proxy setup"
echo "========================="
echo ""
read -rp "Real ChainLP URL (e.g. https://chainlp.yourco.com): " REMOTE_URL

echo ""
echo "How does that ChainLP log you in?"
echo "  1) Username + password"
echo "  2) A single API token/key"
read -rp "Enter 1 or 2: " CHOICE

case "$CHOICE" in
  1)
    read -rp "Username: " USERNAME
    read -rsp "Password: " PASSWORD
    echo ""
    ENCODED=$(printf '%s' "$USERNAME:$PASSWORD" | base64 | tr -d '\n')
    AUTH_HEADER="Basic $ENCODED"
    ;;
  2)
    read -rp "API token/key: " TOKEN
    AUTH_HEADER="Bearer $TOKEN"
    ;;
  *)
    echo "Enter 1 or 2." >&2
    exit 1
    ;;
esac

ENV_FILE="$SCRIPT_DIR/.env"
{
  echo "CHAINLP_REMOTE_URL=$REMOTE_URL"
  echo "CHAINLP_REMOTE_AUTH_HEADER=$AUTH_HEADER"
} > "$ENV_FILE"
echo ""
echo "Wrote $ENV_FILE"

echo "Starting chainlp-write-proxy..."
(cd "$SCRIPT_DIR" && docker compose up -d)

echo ""
echo "Done. In your CI pipeline (any platform - GitLab/GitHub/Azure), set:"
echo "  CHAINTEST_GENERATOR_CHAINLP_ENABLED=true"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://localhost:8086/"
echo ""
echo "If your CI job runs inside a Docker container (a 'docker' executor/"
echo "runner), use this instead:"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:8086/"
echo ""
echo "Re-run this script any time to change the URL or credential - it"
echo "overwrites .env and restarts the container."
