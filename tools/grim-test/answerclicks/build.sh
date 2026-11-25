#!/usr/bin/env bash
# Builds the AnswerClicks test plugin into the test server's plugins folder (setup.sh runs it).
set -euo pipefail
cd "$(dirname "$0")"
source ../versions.env
out=$(mktemp -d)
# The server jar too: the slots mode uses the game's own classes (Mojang names, as Paper runs since 1.20.5).
cp=$(find ../server/libraries ../server/versions -name '*.jar' | tr '\n' ':')
"${JAVA%/java}/javac" -d "$out" -cp "$cp" --release 21 src/dev/myriadclient/grimtest/AnswerClicks.java
cp plugin.yml "$out/"
printf 'paperweight-mappings-namespace: mojang\n' > "$out/manifest.txt"
"${JAVA%/java}/jar" --create --file ../server/plugins/answer-clicks.jar --manifest "$out/manifest.txt" -C "$out" dev -C "$out" plugin.yml
rm -rf "$out"
echo "built server/plugins/answer-clicks.jar"
