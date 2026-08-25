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
    echo ""
    echo "ChainLP is up: http://localhost:$PORT/"
    echo "Set this in your Katalon project's Include/config/chaintest/chaintest.properties:"
    echo "  chaintest.generator.chainlp.enabled=true"
    echo "  chaintest.generator.chainlp.host.url=http://localhost:$PORT/"
    exit 0
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
