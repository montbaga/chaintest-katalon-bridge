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
# Strip a trailing slash - nginx's proxy_pass treats any URI baked into
# $chainlp_upstream (even just "/") as an override for every request's
# path, silently sending "/" instead of the real endpoint on every push.
# Confirmed directly: a URL ending in "/" here causes every ChainLP write
# to arrive at "/" instead of its real API path, failing with 405.
REMOTE_URL="${REMOTE_URL%/}"

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
cd "$SCRIPT_DIR"

CANDIDATE_PORTS=(8086 8091 8096 8101 8106)
CHOSEN_PORT=""
for PORT in "${CANDIDATE_PORTS[@]}"; do
  export CHAINLP_WRITE_PROXY_PORT="$PORT"
  set +e
  OUTPUT=$(docker compose up -d 2>&1)
  STATUS=$?
  set -e
  if [ $STATUS -eq 0 ]; then
    CHOSEN_PORT="$PORT"
    break
  fi
  echo "$OUTPUT"
  if echo "$OUTPUT" | grep -qi "port is already allocated\|ports are not available"; then
    echo "Port $PORT is already in use on this machine - trying the next one..."
    continue
  fi
  echo "docker compose up failed for a reason unrelated to the port - see the error above."
  exit 1
done

if [ -z "$CHOSEN_PORT" ]; then
  echo "All candidate ports (${CANDIDATE_PORTS[*]}) are already in use."
  echo "Pick a free one yourself and re-run with:"
  echo "  CHAINLP_WRITE_PROXY_PORT=<port> docker compose up -d"
  exit 1
fi

echo ""
echo "Done. In your CI pipeline (any platform - GitLab/GitHub/Azure), set:"
echo "  CHAINTEST_GENERATOR_CHAINLP_ENABLED=true"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://localhost:$CHOSEN_PORT/"
echo ""
echo "If your CI job runs inside a Docker container (a 'docker' executor/"
echo "runner), use this instead:"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:$CHOSEN_PORT/"
echo ""
echo "Re-run this script any time to change the URL or credential - it"
echo "overwrites .env and restarts the container."
