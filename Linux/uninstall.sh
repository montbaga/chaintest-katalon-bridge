#!/usr/bin/env bash
# Removes a ChainTest-Katalon Bridge installation from a Katalon Studio project.
#
# Usage:
#   ./uninstall.sh /path/to/katalon/project [--remove-config]
#
# Reads <project>/.chaintest-bridge/manifest.txt (written by install.sh) and
# deletes exactly the files it recorded. Never deletes chaintest-results/ or
# chaintest-report/. By default Include/config/chaintest/*.json|properties
# are kept so a future reinstall doesn't lose your settings; pass
# --remove-config to delete them.

set -euo pipefail

PROJECT_PATH="${1:-}"
REMOVE_CONFIG=false
for arg in "$@"; do
    if [ "$arg" = "--remove-config" ]; then
        REMOVE_CONFIG=true
    fi
done

if [ -z "$PROJECT_PATH" ]; then
    read -r -p "Enter the path to your Katalon Studio project: " PROJECT_PATH
fi
if [ -z "$PROJECT_PATH" ]; then
    echo "Usage: $0 /path/to/katalon/project [--remove-config]" >&2
    exit 1
fi
if [ ! -d "$PROJECT_PATH" ]; then
    echo "ProjectPath does not exist: $PROJECT_PATH" >&2
    exit 1
fi
PROJECT_PATH="$(cd "$PROJECT_PATH" && pwd)"

MANIFEST_FILE="$PROJECT_PATH/.chaintest-bridge/manifest.txt"
if [ ! -f "$MANIFEST_FILE" ]; then
    echo "No install manifest found at $MANIFEST_FILE - this project doesn't look like it has the bridge installed via install.sh." >&2
    exit 1
fi

VERSION="$(head -n1 "$MANIFEST_FILE")"
echo "Uninstalling ChainTest-Katalon Bridge v$VERSION from: $PROJECT_PATH"

CONFIG_RELATIVE_PATHS="Include/config/chaintest/chaintest.properties
Include/config/chaintest/failure-tags.json"

tail -n +2 "$MANIFEST_FILE" | while IFS= read -r relativePath; do
    [ -z "$relativePath" ] && continue

    if [ "$REMOVE_CONFIG" = false ] && echo "$CONFIG_RELATIVE_PATHS" | grep -qx "$relativePath"; then
        echo "  KEEP (config; pass --remove-config to delete): $relativePath"
        continue
    fi

    targetFile="$PROJECT_PATH/$relativePath"
    if [ -f "$targetFile" ]; then
        rm -f "$targetFile"
        echo "  REMOVED  $relativePath"
    fi
done

for dir in "Keywords/chaintest" "Include/config/chaintest" "Test Listeners" "Drivers"; do
    fullDir="$PROJECT_PATH/$dir"
    if [ -d "$fullDir" ] && [ -z "$(ls -A "$fullDir")" ]; then
        rmdir "$fullDir"
        echo "  REMOVED  $dir/ (now empty)"
    fi
done

rm -f "$MANIFEST_FILE"
MANIFEST_DIR="$PROJECT_PATH/.chaintest-bridge"
if [ -d "$MANIFEST_DIR" ] && [ -z "$(ls -A "$MANIFEST_DIR")" ]; then
    rmdir "$MANIFEST_DIR"
fi

echo ""
echo "Uninstall complete."
echo "Note: chaintest-results/ and chaintest-report/ (generated output) were left in place - delete them manually if you want them gone too."
