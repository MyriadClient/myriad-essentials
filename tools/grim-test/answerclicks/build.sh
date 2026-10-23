#!/usr/bin/env bash
# Builds the AnswerClicks test plugin into the test server's plugins folder (setup.sh runs it).
set -euo pipefail
cd "$(dirname "$0")"
source ../versions.env
out=$(mktemp -d)
cp=$(find ../server/libraries -name '*.jar' | tr '\n' ':')
"${JAVA%/java}/javac" -d "$out" -cp "$cp" --release 21 src/dev/myriadclient/grimtest/AnswerClicks.java
cp plugin.yml "$out/"
"${JAVA%/java}/jar" --create --file ../server/plugins/answer-clicks.jar -C "$out" .
rm -rf "$out"
echo "built server/plugins/answer-clicks.jar"
