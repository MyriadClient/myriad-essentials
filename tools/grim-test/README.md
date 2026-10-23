# Grim test server

A local server set up like 2b2t as far as is publicly known, for checking what Grim makes of Myriad's modules
before anyone tries them there. Scripts drive the server (`./cmd`) and the dev client (`./client`), and read Grim's
flags (`./flags`), so a test can be run from a terminal (or by an agent) start to finish.

Needs JDK 21 for the server (path in `versions.env`) and Python 3.

## How close it is to 2b2t

| Matches | Unknown, left at defaults |
|---|---|
| Paper 1.21.4 with ViaVersion and ViaBackwards, so newer and older clients join | 2b2t's Grim version and its config (it may be a fork) |
| GrimAC, current build | Whether 2b2t turns on Grim's experimental checks (`./start.sh --experimental` to try with them) |
| 2b2t's world seed, hard survival, PvP, no spawn protection, Nether and End | Paper tweaks, the queue proxy, plugins |
| About 2b2t's ping, with `./lag.py` | |
| 2b2t answering every inventory click, with `answerclicks` (below) | |

Where 2b2t is known to differ from current Grim, the server is set like 2b2t: Grim's MultiActionsC takes inventory
clicks over held movement keys and jump (live captures on 2b2t, 2026-10-14, showed its Grim doesn't judge them). The
test players get `grim.nomodifypacket.multiactionsc` (through `myriad.like2b2t` in `server/permissions.yml`), so it
still flags them but cancels nothing, and `./suite` lets a keys-only flag pass while a sprinting or sneaking one fails.
Dev clients click on `localhost` as on 2b2t (`-Dmyriad.noInputRuleHosts`). For current Grim as shipped: `./start.sh
--strict`, the client with `-Pstrict` (`./clients N --strict`), and `GRIM_STRICT=1 ./suite`.

A vanilla server sends nothing back for an inventory click that came out as the client predicted; 2b2t answers every
one, so the later clicks of a burst carry an old state id and the whole inventory comes back after each, a few clicks
behind. The AnswerClicks plugin (`answerclicks/`, built by `start.sh` once the server has its libraries) does the same:
`./cmd "answerclicks on"`, or `ANSWER_CLICKS=on ./suite`, which also turns it off for a plain run. `answerclicks pre`
is harsher than any real server: it resends the inventory as it was before each click, which leaves the client
showing the click undone until something else updates the slot (a stress test; a module acting on that view clicks
again).

Passing here means "passes current Grim as configured here", not "safe on 2b2t". Grim alerts on every violation in
this setup (it normally waits for several); that only changes the messages, not what it cancels or sets back.

## Use

```bash
./setup.sh                 # once: downloads Paper, Grim, ViaVersion and ViaBackwards into server/ (pinned in versions.env)
./start.sh                 # starts in the background (--experimental for Grim's experimental checks)
./lag.py 100 15            # optional, in another terminal: localhost:25566 with 100 ms each way, 15 ms jitter

# from the repo root: the dev client, straight into the server
./gradlew runClient -PquickPlayServer=localhost:25565 -Pusername=GrimTester   # as 1.20.4 through ViaFabricPlus; -Pvia=native for 26.2
./cmd "op GrimTester"      # once; ops still get checked (Grim only exempts grim.exempt), and see alerts in chat
```

Then a test is: set the scene, mark the log, act, read the flags.

```bash
./cmd "clear GrimTester" "item replace entity GrimTester armor.chest with elytra" "tp GrimTester 0 220 0 0 0"
./flags mark
./client ".set elytra_fly type altitude" ".toggle elytra_fly on" "wait 200" status ".toggle elytra_fly off"
./flags                    # "no flags", or each flag with Grim's details (exit code 1)
```

| Script | Does |
|---|---|
| `./cmd "<command>" ...` | runs server console commands over RCON and prints their output |
| `./client "<step>" ...` | runs steps in the dev client and prints what it logged (`./client` alone lists the steps) |
| `./flags mark`, `./flags`, `./flags all`, `./flags follow` | Grim's flags since the mark, this run, or live |
| `./lag.py [ms] [jitter]` | a latency proxy on the lag port |
| `./setup.sh --update` | shows the newest builds, to move `versions.env` to on purpose |
| `./stop.sh` | stops the server |
| `./reload` | swaps freshly compiled classes into the running client(s) (see below) |
| `./clients N`, `./clients stop` | starts (or stops) dev clients 2..N beside the first, for `./suite --workers N` |

The client steps come from `DevConsole` in core, which dev runs turn on: Myriad commands (`.toggle kill_aura on`),
server commands as the player (`/gamemode survival`), `hold forward,sprint,jump 40`, `look <yaw> <pitch>`,
`select <1-9>`, `attack`, `use`, `wait <ticks>`, `respawn`, `status` (with what's on the cursor and which screen is
open), `closescreen`, `cursor slot <n>` (the mouse over a slot of the open screen, whatever the window size; then
`mouse 0 click` or `mouse 2 press` ... `mouse 2 release`), `connect <host:port>` (from a disconnect
screen: `./cmd "kick GrimTester"` first, then `connect localhost:25566` to play through `./lag.py`), and
`selftest [ticks] [-module]`, which turns every module on for a while and off again and reports any that failed or
whose handlers threw.

Recast's obstacle tests take Baritone's path when the dev client has it (a Baritone jar in `run/mods`, e.g. copied from
your own instance) and the mine-and-fill path when it doesn't; the Baritone-only tests skip without it. Run both ways
after changing Recast's obstacle handling.

Grim simulates each player as the version they joined with, so test as 1.20.4 (the default) and, for anything
version-dependent, again with `-Pvia=native`; `./cmd "viaversion list"` shows what the server sees. Elytra wear needs
ping to show: a landing the server misses (its packet handled in the same server tick as the next hop's) lets its
glide run on, so run the elytra tests through `./lag.py 100 80` too (and `./cmd "tick rate 14"` for 2b2t's TPS).

## The Essentials suite

```bash
./suite              # every test (about 3 minutes)
./suite kill_aura    # the tests whose names contain a word
./suite --list
```

With the server and the dev client running, `./suite` turns every module off, then for each test builds a scene of
its own (mostly on a stone floor around x=1000, away from the rest of the world), runs the client steps, checks the
result on the server (the husk is dead, the block is gone, the totem is in the off hand, the elytra didn't wear) and
fails the test if Grim flagged anything while the module ran. It covers the self-test, Kill Aura (holding and switch),
Packet Mine, Scaffold, Offhand, Auto Armor, Auto Eat, Stack Replenish, Recast, Altitude and No Durability, and that
Auto detects Grim. Run it after changing core or Essentials; add a test when you add a module.

## Iterating fast

Restarting the game for every change is the slow part. Start the client once with `-Photswap` (the JetBrains Runtime 25,
found in `/usr/lib/jvm/jbr*` or as a Gradle toolchain, with a debug port), then after each change:

```bash
./gradlew classes && tools/grim-test/reload       # from the repo root; with -PcoreDir, ../myriad's :core:classes too
```

`./reload` swaps in every class that changed since the last reload, in every client listening (one call for all of
them), added and removed methods and fields included. It can't run constructors or static initialisers again (a module's
new setting or default, a new static field's value) or load a mixin change: restart for those. Classes the game hasn't
loaded yet load from the new files by themselves.

The suite runs side by side on several clients, each testing in a world of its own (the first in the overworld,
GrimTester2.. in flat copies made by `datapack/myriad-tests`, so every test keeps its coordinates):

```bash
./clients 3                 # GrimTester2 and GrimTester3, from run2 and run3 (--hotswap for reload ports 5006, 5007)
./suite --workers 3         # the tests shared out by how long each took last time (.suite-times.json)
./clients stop
```

Extra clients run the build the first client's `runClient` set up (Loom's launch files), so start the first one once
after changing dependencies. `./client --run run2 ...` drives a single extra client.

The Printer tests (`./suite printer`) need Litematica in the client: start it with `-Plitematica` (which leaves out
Xaero's maps, as they crash together). Each builds a row of blocks with commands, saves it as a Litematica schematic
with `.printscene`, and has the Printer build the copy while the tester stands, walks, sprints or sprint-jumps past;
the server compares the two rows (blocks not placed and blocks placed wrong are told apart) and checks the hotbar and
off hand came back as they were. The `2b2t` ones print with 2b2t's settings (Rotate off, Air Place on): this server's
Grim would cancel those clicks (RotationPlace, AirLiquidPlace, PositionPlace), which 2b2t's takes, so for them the tester
is deopped into `myriad.lenientplace` (see `start.sh`), where those checks only flag. For 2b2t's lag, run them through
`./lag.py 120 25` (join localhost:25566).
