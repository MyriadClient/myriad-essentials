#!/usr/bin/env bash
# Starts the server in the background. --experimental turns on Grim's experimental checks (2b2t's setting is unknown).
# --strict: current Grim as shipped. By default it's set like 2b2t's (see README): clicks over held keys aren't cancelled.
set -euo pipefail
cd "$(dirname "$0")"
source versions.env
[[ -f server/paper.jar ]] || ./setup.sh
if ./cmd list >/dev/null 2>&1; then echo "already running"; exit 0; fi

grim_config=server/plugins/GrimAC/config.yml
experimental=false; strict=false
for arg in "$@"; do
	case "$arg" in
		--experimental) experimental=true;;
		--strict) strict=true;;
	esac
done
if [[ -f "$grim_config" ]]; then
	# Every flag, with its details, in the console log (where ./flags reads them).
	python3 - "$grim_config" "$experimental" "$strict" <<'PY'
import re, sys
path, experimental, strict = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
# An earlier try at the 2b2t setting (Grim 2.3.74 has no cancelVL for MultiActionsC): gone, see permissions.yml below.
s = re.sub(r'(?m)\n*^# myriad: like 2b2t\nMultiActionsC:\n[ \t]+cancelVL: -?\d+\n', '\n', s)
s = re.sub(r'(?m)^(alerts:\n(?:[ \t]+#.*\n)*[ \t]+print-to-console:) \w+', r'\1 true', s)
s = re.sub(r'(?m)^(verbose:\n(?:[ \t]+#.*\n)*[ \t]+print-to-console:) \w+', r'\1 true', s)
s = re.sub(r'(?m)^experimental-checks: \w+', 'experimental-checks: ' + experimental, s)
s = re.sub(r'(?m)^check-for-updates: \w+', 'check-for-updates: false', s)
open(path, 'w').write(s)
PY
	# 2b2t's Grim takes an inventory click whatever keys the last input held (live captures, 2026-10-14); current Grim's
	# MultiActionsC cancels it. Like 2b2t: ops (the test players) get Grim's no-modify permission for that check, so it
	# only flags (the suite lets a keys-only flag pass; over sprinting or sneaking it still fails).
	python3 - server/permissions.yml "$strict" <<'PY'
import re, sys
path, strict = sys.argv[1], sys.argv[2]
try:
    s = open(path).read()
except FileNotFoundError:
    s = ""
s = re.sub(r'(?ms)^# myriad: like 2b2t.*?^# myriad: end\n', '', s)
if strict == "false":
    s = s.rstrip("\n") + ("\n" if s.strip() else "") + "# myriad: like 2b2t (Grim registers its own nodes default false: granted through this parent)\nmyriad.like2b2t:\n  default: op\n  children:\n    grim.nomodifypacket.multiactionsc: true\n# myriad: end\n"
open(path, "w").write(s if s.strip() else "")
PY
	# Alert on every violation (Grim normally waits for several). Alerts are only messages: what Grim cancels or sets
	# back doesn't change.
	sed -i -E 's/"[0-9]+:[0-9]+ \[alert\]"/"1:1 [alert]"/' server/plugins/GrimAC/punishments.yml
fi

# The suite's flat worlds, one per extra test client (./suite --workers): datapack dimensions, made on the first start.
mkdir -p server/world/datapacks
rm -rf server/world/datapacks/myriad-tests
cp -r datapack/myriad-tests server/world/datapacks/

cd server
nohup "$JAVA" -Xms2G -Xmx3G -jar paper.jar nogui > console.log 2>&1 < /dev/null &
echo $! > server.pid
cd ..
echo -n "starting"
for _ in $(seq 1 120); do
	if grep -q 'Done (' server/console.log 2>/dev/null; then echo; echo "up on localhost:$PORT (lagged: localhost:$LAG_PORT via ./lag.py)"; exit 0; fi
	if ! kill -0 "$(cat server/server.pid)" 2>/dev/null; then echo; echo "server exited:"; tail -20 server/console.log; exit 1; fi
	echo -n "."; sleep 1
done
echo; echo "still starting; see server/console.log"
