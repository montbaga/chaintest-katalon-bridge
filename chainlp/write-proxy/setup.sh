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
    # docker compose up -d returning success does NOT guarantee the
    # container stays up - it can report success and then crash moments
    # later. A single fixed-delay check isn't reliable either: confirmed
    # directly against chainlp-proxy that a container can still show
    # State.Running=true 2 seconds in, then die 3 seconds after that.
    # Poll for several seconds instead of trusting one snapshot.
    RUNNING=false
    for _ in 1 2 3 4 5; do
      sleep 1
      set +e
      CHECK_NOW=$(docker inspect -f '{{.State.Running}}' chaintest-katalon-chainlp-write-proxy 2>/dev/null)
      set -e
      if [ "$CHECK_NOW" != "true" ]; then
        RUNNING=false
        break
      fi
      RUNNING=true
    done
    if [ "$RUNNING" = "true" ]; then
      CHOSEN_PORT="$PORT"
      break
    fi
    echo "docker compose up -d reported success, but chainlp-write-proxy isn't actually running - its own logs:"
    docker logs chaintest-katalon-chainlp-write-proxy 2>&1 | tail -15
    OUTPUT=$(docker logs chaintest-katalon-chainlp-write-proxy 2>&1)
  else
    echo "$OUTPUT"
  fi

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

# Report whatever this actually got published on, not an assumed
# "localhost" - only wrong if ports: was hand-edited to bind a different
# host. Real host bindings (a specific IP) are reported exactly as bound.
BOUND_HOST=$(docker port chaintest-katalon-chainlp-write-proxy 80/tcp 2>/dev/null | head -1 | cut -d: -f1)
if [ -z "$BOUND_HOST" ] || [ "$BOUND_HOST" = "127.0.0.1" ]; then
  BOUND_HOST="localhost"
elif [ "$BOUND_HOST" = "0.0.0.0" ]; then
  # "0.0.0.0" means published on every network interface, not just
  # loopback - someone deliberately opened this up so other machines can
  # reach it, which makes "localhost" actively wrong advice here. See
  # up.sh's matching comment for why `hostname -I` first, `ifconfig` as
  # fallback, is a reliable enough guess with no manual lookup needed.
  DETECTED_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
  if [ -z "$DETECTED_IP" ]; then
    DETECTED_IP=$(ifconfig 2>/dev/null | awk '/inet /{print $2}' | grep -v '^127\.' | head -1)
  fi
  if [ -n "$DETECTED_IP" ]; then
    BOUND_HOST="$DETECTED_IP"
  else
    BOUND_HOST="localhost"
  fi
fi

echo ""
if [ "$BOUND_HOST" != "localhost" ] && [ "$BOUND_HOST" != "127.0.0.1" ]; then
  echo "(This is this machine's own detected network IP - double-check it's the right one and actually reachable from wherever you need it before sharing it, since a server with more than one network adapter or VPN active can have several.)"
  echo ""
fi
echo "Done. In your CI pipeline (any platform - GitLab/GitHub/Azure), set:"
echo "  CHAINTEST_GENERATOR_CHAINLP_ENABLED=true"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://$BOUND_HOST:$CHOSEN_PORT/"
echo ""
echo "If your CI job runs inside a Docker container (a 'docker' executor/"
echo "runner), use this instead:"
echo "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:$CHOSEN_PORT/"
echo ""
echo "Re-run this script any time to change the URL or credential - it"
echo "overwrites .env and restarts the container."
