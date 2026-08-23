#!/usr/bin/env bash
# Generates .htpasswd (in this same folder), the login required for
# remote (tunnel) access to ChainLP. Local access on this machine
# (http://localhost:8085) never needs this - only chainlp-tunnel-auth
# reads it.
#
# Usage:
#   ./generate-htpasswd.sh <username>
# Prompts for the password (hidden input) rather than taking it as an
# argument, so it never ends up in shell history.

set -euo pipefail

USERNAME="${1:-}"
if [ -z "$USERNAME" ]; then
    echo "Usage: $0 <username>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_FILE="$SCRIPT_DIR/.htpasswd"

read -r -s -p "Password for '$USERNAME': " PASSWORD
echo ""

echo "Generating bcrypt hash via a throwaway 'httpd:alpine' container (needs Docker, nothing installed locally)..."
LINE=$(docker run --rm httpd:alpine htpasswd -Bbn "$USERNAME" "$PASSWORD")
PASSWORD=""

if [ -z "$LINE" ]; then
    echo "htpasswd produced no output - is Docker running?" >&2
    exit 1
fi

printf '%s' "$LINE" > "$OUT_FILE"
echo "Wrote $OUT_FILE"
echo ""
echo "Next, from the chainlp/ folder (one level up): docker compose --profile tunnel up -d"
echo "(Re-run this script any time to change the password - it overwrites the file.)"
