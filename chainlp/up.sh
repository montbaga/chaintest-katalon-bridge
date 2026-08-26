#!/usr/bin/env bash
# Brings up ChainLP + chainlp-proxy, automatically trying a different
# port if 8085 is already taken on this machine - no manual editing of
# docker-compose.yml needed for that case. Run this instead of
# `docker compose up -d` directly.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

CANDIDATE_PORTS=(8085 8090 8095 8100 8105)

for PORT in "${CANDIDATE_PORTS[@]}"; do
  echo "Trying ChainLP on port $PORT..."
  export CHAINLP_PROXY_PORT="$PORT"
  OUTPUT=$(docker compose up -d 2>&1)
  STATUS=$?
  echo "$OUTPUT"
  if [ $STATUS -eq 0 ]; then
    # docker compose up -d returning success does NOT guarantee the
    # container stays up - it can report success and then crash moments
    # later. A single fixed-delay check isn't reliable either: confirmed
    # directly that a container can still show State.Running=true 2
    # seconds in, then die 3 seconds after that (nginx's own entrypoint
    # takes a variable amount of time before it actually attempts its
    # port bind and fails). Poll for several seconds instead of trusting
    # one snapshot.
    RUNNING=false
    for _ in 1 2 3 4 5; do
      sleep 1
      CHECK_NOW=$(docker inspect -f '{{.State.Running}}' chaintest-katalon-chainlp-proxy 2>/dev/null)
      if [ "$CHECK_NOW" != "true" ]; then
        RUNNING=false
        break
      fi
      RUNNING=true
    done
    if [ "$RUNNING" = "true" ]; then
      # Report whatever this actually got published on, not an assumed
      # "localhost" - if ports: was hand-edited to bind a different host
      # (e.g. a real IP for Scenario E/testing), "localhost" would be
      # silently wrong here otherwise. "0.0.0.0" isn't itself a URL you can
      # browse to, so it still falls back to localhost - real host bindings
      # (a specific IP) are reported exactly as bound.
      BOUND_HOST=$(docker port chaintest-katalon-chainlp-proxy 80/tcp 2>/dev/null | head -1 | cut -d: -f1)
      if [ -z "$BOUND_HOST" ] || [ "$BOUND_HOST" = "0.0.0.0" ] || [ "$BOUND_HOST" = "127.0.0.1" ]; then
        BOUND_HOST="localhost"
      fi
      echo ""
      echo "ChainLP is up: http://$BOUND_HOST:$PORT/"
      echo "Set this in your Katalon project's Include/config/chaintest/chaintest.properties:"
      echo "  chaintest.generator.chainlp.enabled=true"
      echo "  chaintest.generator.chainlp.host.url=http://$BOUND_HOST:$PORT/"
      exit 0
    fi
    echo "docker compose up -d reported success, but chainlp-proxy isn't actually running - its own logs:"
    docker logs chaintest-katalon-chainlp-proxy 2>&1 | tail -15
    OUTPUT=$(docker logs chaintest-katalon-chainlp-proxy 2>&1)
  fi
  if echo "$OUTPUT" | grep -qi "port is already allocated\|ports are not available"; then
    echo "Port $PORT is already in use on this machine - trying the next one..."
    continue
  fi
  echo "docker compose up failed for a reason unrelated to the port - see the error above."
  exit 1
done

echo ""
echo "All candidate ports (${CANDIDATE_PORTS[*]}) are already in use."
echo "Pick a free one yourself and run:"
echo "  CHAINLP_PROXY_PORT=<port> docker compose up -d"
exit 1
