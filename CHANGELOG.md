# Changelog

Myriad Essentials is versioned on its own, apart from [Myriad](https://github.com/MyriadClient/myriad-core) core. Each
release says which core it needs (`myriad_version` in `gradle.properties`, the `depends` in `fabric.mod.json`).

## Unreleased

Needs Myriad 1.2.0.

- **Middle Click** sends no swing with a pearl, rocket or bottle on 2b2t (Myriad's 2b2t profile, which wants none),
  and the item goes back out of your hand in the same tick there.

## 1.2.0

Needs Myriad 1.1.1, whose item uses go out in their own tick when joined as 1.17 to 1.20.5.

- **No Slow** (Movement): full speed while eating, drinking, blocking or drawing a bow, and through cobwebs and sweet
  berry bushes. Under Grim, one tick in three goes at full speed while you use an item, which its NoSlow check lets
  pass (it flags two fast ticks in a row it can't explain): about twice the speed the item allows (3.0 blocks in 34
  ticks of walking while eating, against about 1.5). Webs under Grim too, at full speed (6.5 blocks in 30 ticks of
  walking through them, 8.4 sprinting, against under one): Grim always slows you in a web its world has, so the webs
  round you are reported broken, which makes it hold air there until the server's answer comes back, though the
  server refuses and nothing changes on screen, and each is reported again as that answer arrives. The reports pass
  its break checks and stay well under the server's packet limit (about 140 a second at most, of the 500 Paper
  allows). No sprint jumps into webs, whose push the server's own movement check sets back, and none while the block
  you last started breaking still stands (a tap on stone, given up), whose break time Grim measures every web's
  against: you go slowed then, until you break something.
- **Auto Reconnect** (Player): joins the server you were on again when you're kicked or the connection drops,
  after Delay seconds (5), and keeps trying while it can't get in. The disconnect screen shows the countdown on a
  button that joins at once; going back to the server list stops it. Never after Auto Disconnect made you leave.
- **Auto Portal** is in the Automation window, with the Automation tag, beside the other modules that do a job by
  themselves.

## 1.1.0

Needs Myriad 1.1.0.

- **The windows are named after their tags again**: Combat, Movement (was Travel), World (was Building & Mining),
  Player (was Inventory), Render (was Visuals), and a new **Automation** window and tag for Auto Eat, Auto Tool, Auto
  Walk, Auto Mend, Auto Sort and the Printer. Each module carries the tag of its window (Velocity is Combat; Packet
  Mine, Reach and Wall Interact are World). The renamed windows open fresh, placed beside your others; move them once.
- **Renamed**: Lock Rotation is **Rotation Lock**, Portal Builder **Auto Portal**, ESP **Entity ESP**, Storage
  **Storage ESP**, Blocks **Block ESP**, Stack Replenish **Replenish**. Their settings, keybinds and on/off state carry
  over; commands take the new names (`.toggle auto_portal`).
- **Baritone Render is part of the Baritone module**: its **Custom Render** switch (in Render, after Baritone's own
  options) draws the path, the blocks it will break and place, and the goal your way while the module is on, with the
  Path, Blocks and Goal options showing while it's on. Baritone Render's own settings don't carry over.
- **Freecam and Free Look turn each other off**: on together, they fought over the camera.
- **Armor shows your hands too**: the main hand and the off hand before the armour (each can be switched off), stacks
  with their count, and durability as the uses left by default (Number), beside Percent, Bar, Both and None.
- **Looking At is a wide strip**: the name on top and the details beside each other on one row, and while the block
  you look at is being broken (by your hand or a module, Packet Mine say) a bar fills along the bottom, its percentage
  shows by the name and the border's gradient turns. **Break Progress** switches it off.

## 1.0.0

Needs Myriad 1.0.0.

- **On Myriad 1.0**, whose API now holds for every 1.x release: Essentials used none of what 1.0 removed, so its
  modules are as in 0.11.0.

## 0.11.0

Needs Myriad 0.11.0.

- **Every module draws in Myriad's render mode** (Shader or Box, picked once in the Render window): ESP, Storage,
  Blocks, Block Highlight, Packet Mine, Kill Aura, Air Place, Printer, Portal Builder and Baritone Render. Their own
  Mode options are gone, and each module's look is in its **Render** options, which show those for the mode that's on.
  Saved looks carry over (a box's *Lines* shape becomes no fill, *Fill* no edges; dots keep their spacing and size).
- **ESP**: *Box Style* (3D, 2D, Model) for Box mode, with Fade In and the 2D Health Bar. **Storage** and **Blocks** are
  simpler: no lids, no merging, no mode; one box (or one outline) per container (a double chest is one) or block, by
  its shape. **Block Highlight** is one colour and a look. **Air Place** gets a Render group (Show Target, Color).
  **View Model**'s hand and item highlights moved into its Render group (Hand, Item, each with its colour and look),
  and show in either mode.

- **Previews in the settings of the modules that draw**: ESP (a zombie), Blocks (the first block picked), Block
  Highlight (a block), Storage (a chest) and View Model (your hands and held items, moving as you change its settings).
  Drag to turn, right-drag to move, scroll to zoom, double-click to put back.
- **The modules draw on every preview as in the world**, in either render mode, so a Preview window on a workspace
  shows the look of everything that's on: ESP on a mob or you, Storage on a container or a chest minecart, boat or pack
  animal (shulker boxes in their dye), Blocks on a block it's set to show, View Model's hand and item highlights on
  your hands, and Block Highlight on a block nothing else shows (as if you looked at it).

## 0.10.0

Needs Myriad 0.10.0.

- **Builds on Myriad's addon Gradle plugin** (`dev.myriadclient.addon` 0.10.0): the build file keeps only what's
  Essentials' own (ViaFabricPlus, Xaero's maps and Litematica for the dev client, the protocol it joins with).

- **Printer, Portal Builder, Auto Sort and Auto Mend join Essentials**, from Myriad Automation (Auto Mend was its XP
  Repair). Printer and Portal Builder are in Building & Mining, Auto Sort and Auto Mend in Inventory. The Printer needs
  Litematica and MaLiLib installed; nothing else does. Settings saved under Automation's modules don't carry over.
  The tests came with them: `.printtest` (in a dev client started with `-Plitematica`), `.portaltest`, `.invtest sort`
  and `.invtest mend`; all pass in Essentials (portal lit in 0.55 s on 2b2t settings, sort 4/4, mend OK, print 282
  right and 0 wrong). Litematica 0.28.8 with Xaero's Minimap and World Map crashes the game at start (each alone is fine), so
  the dev client leaves the maps out with `-Plitematica`.
- **Printer tests in the Grim suite**, for printing while moving: standing, walking, sprinting and sprint-jumping past
  a row of 24 mixed blocks, with Rotate on and with 2b2t's settings, checking every block (not placed and placed
  wrong told apart) and that the hotbar and off hand come back as they were. They found Myriad core's builds leaving
  the hotbar full of blocks and, with lag, placing wrong blocks (both fixed in core 0.10.0). `.printscene` (dev
  clients with Litematica) saves a box as a schematic and places it elsewhere, so the tests run on any server; the test
  server's `myriad.lenientplace` lets the 2b2t-setting runs be judged by where blocks go rather than by checks 2b2t's
  Grim doesn't enforce.
- **Portal Builder lights with a full hotbar.** The flint and steel came into an empty hotbar slot or the obsidian's,
  and with neither it asked you to free one; it's now borrowed into any slot but the one you hold and put back after.
- **More build tests in the Grim suite:** `printer full hotbar 2b2t` (every hotbar slot holding something of yours),
  `portal_builder full hotbar` and `portal_builder air place`, each checking the hotbar and off hand come back as
  they were; all pass on a direct connection and through `lag.py 120 25`. The Middle Click inventory test waits for
  its items to reach the client before clicking, so it passes through `lag.py` too.
- **Auto Sort shows the sorted container at once** (core 0.10.0), instead of items flickering across it and the cursor
  while the clicks go out, and a click of yours stops it. Suite: `auto_sort` and `auto_sort full inventory` sort a
  double chest of 40 scattered part stacks and check on the server it's merged and packed with nothing lost (5.2 s).
- **How a module draws is in one Render group, last, everywhere.** ESP, Blocks, Storage and Block Highlight had their
  outline style in a group called Outline (and Block Highlight its box in Box), with the mode and colours in General;
  Packet Mine's colours and looks were spread over Mining, Queue and Auto Rebreak; Kill Aura's target outline sat in
  General. Each now has a Render group with its mode of drawing, colours and style, the box and outline styles sharing
  Fill Opacity and Through Walls. Kill Aura's Render toggle is now Show Target. Packet Mine keeps a full style for the
  block being mined, and colours for the queue and the rebreak block, whose looks (dotted, outlined) are now fixed.
  Saved settings carry over.
- **The Printer gets options.** **Order**: Bottom Up (a layer at a time, as before) or Nearest (what's beside you first).
  **Skip**: blocks of the schematic to leave out. **Walk To Blocks**: once nothing in reach is left, Baritone walks you
  to beside the nearest unbuilt block, on a spot the schematic doesn't need, without mining or placing on the way; a
  spot that didn't do is not tried again for that block. **Restock**: when a block runs out, a stack comes from the
  shulker boxes you carry and the chests, barrels and shulker boxes in reach (within Container Range with Walk To
  Blocks), nearest first, passing over a container that had none lately; printing carries on after. Suite: `printer
  skip` (22/22, glass left out), `printer restock` (both glass from a chest beside the row), `printer walk` (24/24 from
  20 blocks away, no flags).
- **Middle-click sorts in Inventory Tweaks.** In any inventory screen, middle-click over a chest's, barrel's, shulker
  box's, dispenser's or hopper's slots to sort them, or over your inventory to sort it (the hotbar stays as you keep it):
  shown sorted at once, as Auto Sort does it, for sorting by hand without every container sorting itself as it opens.
  Middle Sort and Sort Order in its Mouse group. Suite: `inventory_tweaks middle sort` (a chest) and `inventory_tweaks
  middle sort inventory`, through `lag.py 120 25` too.
- **Sorting never leaves an item on your cursor** (core 0.10.0): with lag a sort could leave a stack on the cursor;
  `auto_sort your click` clicks a chest slot mid-sort, and every sort test now checks the cursor is empty and nothing
  is left in your inventory: 21 of 21 through `lag.py 120 25`.
- **Middle Click takes rockets and pearls from the inventory with a full hotbar, and never fires late.** It only
  borrowed into an empty hotbar slot or one with building blocks, so with a hotbar of tools nothing happened; now the
  item swaps places with any slot but the selected one, and both go back after. A click that couldn't go through kept
  waiting, and fired whenever a stack reached the hotbar, minutes later; now it's dropped after a quarter of a second.
  New suite tests: a pearl and a rocket while gliding, each from the inventory with a full hotbar (both failed before).
- **X Carry is gone.** Items left in the crafting grid come back to your inventory the next time you close it.
- **Packet Mine: the queue and the block Auto Rebreak watches have their own look.** New Queue and Auto Rebreak groups,
  each with a Color and the standard highlight look (outline, glow, fill, gradient, through walls), starting from the
  old ones (the queue faint and dotted, the rebreak block outlined). General's Color is gone; one you'd changed
  carries over to both, at the opacities it drew them with (settings version 4).

## 0.9.0

Needs Myriad 0.9.0.

- **View Model highlights your hand, your held items, or both.** Hand Highlight and Item Highlight each have their own
  on/off, colour and the standard highlight look (outline, glow, fill, gradient); hand and item that look alike get
  one outline around both. Off by default, and while both are off View Model doesn't listen for highlights at all (a
  listener would have Myriad draw vanilla's glowing entities itself).
- **Packet Mine: the mined block's fade is yours.** A new Mining group: Start Color and End Color (the block blends
  from one to the other, opacity too, as it breaks, and stays at End until the server says it's gone) and the
  standard highlight look, starting from the old one (solid fill, no outline). By default it looks as before (accent,
  15% to 65%). Color now only colours the queue and the block Auto Rebreak watches; a Color you'd changed carries over
  to Start and End (settings version 3).
- **Inventory Tweaks moves items with the mouse** (a new Mouse group, each on by default):
  - **Shift Drag**: hold Shift and drag with the left button to shift-click every slot you pass.
  - **Drag Collect**: carrying a stack, start a left drag on a slot of the same item, and every matching slot you
    pass joins your stack until it's full. A drag started anywhere else spreads the stack as vanilla does, and
    vanilla's right drag (one item per slot) is untouched.
  - **Scroll Move**: scroll down over an item to move one to the other inventory (a chest's and yours, or in your own
    inventory the hotbar and the rest), up to bring one back. Never takes from a crafting or furnace output.
  - Every click goes through the screen as your own would, and only when the server takes clicks (on Grim, not while
    you walk with Inventory Move), so a move of several clicks is never left half done. Checked in singleplayer
    against the server's copy of the inventory, and Scroll Move on the Grim test server (five items each way, three
    in one tick, no flags); a failure in any of them turns Inventory Tweaks off rather than crashing the game.
- **No Render: Your Effect Particles** (on by default) hides the potion swirls your own effects give off, which drift
  right in front of the camera in first person. Other players' and mobs' swirls still show.
- **Recast's Baritone hand-off is a job** of Elytra Fly's, so Myriad's Jobs window shows it ("Lane blocked: Baritone ·
  12 blocks to go") and you can pause it there (Baritone pauses) or cancel it (you wait at the block; If Blocked's
  disconnect is left out, since you stopped it). What it does is unchanged: one goal past each obstacle, Baritone
  mining and placing with your blocks, blocked if it can't get there.

## 0.8.0

Needs Myriad 0.8.0.

- **Altitude and Control fly on Myriad's own flights** (`Myriad.elytra().altitude` and `control`), the same code other
  addons fly. Altitude flies as before (on the Grim test server 605 blocks in 30 s with Elytra Swap, 1722 blocks on a
  wearing elytra before landing). Control now shows the server the way you fly, level, and comes down to land when
  your last elytra is wearing out, as Altitude does.
- **Kill Aura's Turn Speed and Packet Mine's mode are Myriad's standard settings.** Packet Mine's Fast switch is now
  Mode: Fast (a saved Fast carries over), with Double Break beside it. Scaffold follows the server's placement rules
  as the rest do; its Rotate switch is unchanged.
- **Flight follows what the server checks:** Control is offered, and No Durability swaps a chestplate, by whether the
  server simulates the glide, so 6b6t's NoCheatPlus and any addon's own anti-cheat profile get the right flight.
- **Module List** keeps a module another module is holding off listed, faded, as "suspended": it's still on as far
  as you're concerned and comes back by itself.
- **Inventory Tweaks** opens shulker boxes and ender chests from your inventory through Myriad's own
  (`Containers.openItem`), so the box always goes back where it was.

## 0.7.0

Needs Myriad 0.7.0.

- **Five windows instead of six categories**, by what the modules are for: **Combat** (Kill Aura, Offhand, Auto Armor,
  Auto Eat, Velocity, Auto Disconnect), **Travel** (Elytra Fly, Elytra Swap, Lock Rotation, Rocket Boost, Auto Walk,
  Inventory Move), **Building & Mining** (Scaffold, Air Place, Packet Mine, Auto Tool, Reach, Wall Interact,
  Baritone), **Inventory** (Inventory Tweaks, Stack Replenish, X Carry, Middle Click) and **Visuals** (ESP, Tracers,
  Nametags, Storage, Blocks, Block Highlight, Full Bright, No Render, Time of Day, View Model, Zoom, Free Look,
  Freecam, Tooltips, Baritone Render). Your layout carries over: each old category window becomes the group that
  took its place, and the new Inventory window opens beside them.
- Modules keep their tags for the launcher (`#combat`, `#render`...); **Reach** is tagged Combat and World as well
  as Player.

## 0.6.0

Needs Myriad 0.6.0.

- **Scaffold**, **Recast** (filling holes) and **Inventory Tweaks** (placing a shulker box to open it) place against
  chests, crafting tables, doors and other blocks that would open or toggle, as if sneaking, without you crouching
  (core's sneak clicks, only for such blocks).
- **Inventory Tweaks** opens the box as itself, so other addons' modules that act on containers you open (sorting)
  leave it alone.
- **Baritone Render** follows the theme by default: the path's gradient runs from the accent to the secondary colour,
  blocks to break are the theme's red and gaps to fill its blue, and the goal's highlight is the accent (they were
  fixed colours). Colours you set yourself stay as they are.
- **Packet Mine**: Fast's description says where its decoy starts go now (above the build limit, core 0.6.0).

## 0.5.0

Needs Myriad 0.5.0.

- **Baritone** (World, only with Baritone installed): Baritone's settings in Myriad's menu, made from Baritone's own
  list and sorted into groups (Breaking & Placing, Movement, Mining, Building, Elytra, Rendering...). While it's on,
  every setting changed from Baritone's default is set in Baritone, for now (its settings file isn't touched), and off
  puts Baritone's own back unless something changed them since. Saved per profile, so control panels and shared
  configs can carry a Baritone setup.
- **Baritone Render** (Render, only with Baritone installed): Baritone's path, work and goal drawn Myriad's way, in place
  of Baritone's own (turned off while it's on). The path is a smooth line with rounded corners and a gradient flowing
  along it; the blocks it will mine and the gaps it will fill on the path ahead are highlighted; and the goal is a ghost
  of you standing there, facing the way you'd arrive, your skin, armour, cape or elytra and what you hold faded, traced
  by the highlight shader (through walls by default), or a highlighted box. Highlights take the usual style options.
  Tested with Baritone mining through a wall and bridging a gap, and through the Grim suite's Recast obstacle tests.
- **Auto Disconnect**: Coords is now Destination, with a Target: Coords (X, Z) or Waypoint, one of your Xaero
  Minimap waypoints, found from whichever dimension you're in. Saved settings move over. X, Z, Within, Floor Y and
  Ceiling Y are typed in a box instead of a slider.
- The dev client runs with Xaero's Minimap and World Map (`-PnoMaps` leaves them out).

## 0.4.1

Needs Myriad 0.4.0.

- **Elytra Fly**: Snap (Off, 45°, 90°), kept for each type, rounds the way it flies: Recast's lane (45° by default,
  as it always snapped; Off now runs the lane exactly the way you face) and, for Altitude and Control (Off by default),
  the heading Lock Rotation holds, so a takeoff a few degrees off still flies the highway or diagonal exactly.
- **Altitude** no longer comes down to land when the server keeps setting you back: it flies on (a dropped glide
  starts again), steadying through a run of setbacks as before, now counting each setback once (Grim resends one every
  couple of ticks until it's taken, so a lag spike counted as several). Live recordings showed it landing after as few
  as 6 real setbacks in 30 s. Use Auto Disconnect's Hard Deck to catch a flight that goes wrong. It still lands when
  the last elytra is wearing out.
- **Auto Disconnect**: a Position group, off by default, for leaving a flight or walk running to somewhere. Hard Deck
  leaves below Floor Y or above Ceiling Y (each once you've been on its safe side in that dimension, so starting outside
  one doesn't), and Coords leaves within Within blocks of X, Z (Use Here fills them in). Left in the air, you're in the
  air when you rejoin; with Myriad 0.4.2, Elytra Swap's chestplate isn't: the elytra goes back on as you leave.

## 0.4.0

Needs Myriad 0.4.0.

- **Elytra Swap**: No Durability is a module of its own, for any flight: Elytra Fly's types and, with When You Fly,
  flying yourself (it was Elytra Fly's No Durability and Auto Armor's No Durability Elytra). Saved settings move over:
  with No Durability on (the default) Elytra Swap comes on, When You Fly from Auto Armor's setting. Elytra Fly says
  once when it flies on Grim with Elytra Swap off.
- **Lock Rotation**: Elytra Fly's Lock Heading as a module (Altitude and Control follow it), locking at takeoff or,
  with Lock At: Turned On, when you turn it on. Turned on where Lock Heading was.
- **Auto Walk** flies Control forward too; Control's own Auto Walk setting is gone.
- **Control panels**: Highway (Recast, Elytra Swap, Auto Eat) and Cruise (Altitude, Elytra Swap, Lock Rotation, Auto
  Eat).
- **Hooks** for other addons: `ElytraSwap.BEHAVIOUR`, `LockRotation.HEADING`, `KillAura.TARGET`,
  `Offhand.FORCE_TOTEM`. Elytra Fly's types have ids (`myriad-essentials:recast`, ...) to match on.
- Code: imports tidied, notifications through `toast(...)`, a leftover of Recast removed.

## 0.3.2

- **Free Look**: turned on outside a world, it waits for one to start from your look; leaving a world gives the camera
  back, so the next one doesn't start from the last one's look or third person. With core 0.3.4, a hold bind let go
  behind a loading screen turns it off too.

## 0.3.1

- A mod icon: one outlined square, a link of Myriad's logo, in Essentials' mint.

## 0.3.0

Needs Myriad 0.3.0.

- Moved to `dev.myriadclient` with core: the packages, and `dev.myriadclient:myriad-essentials` on
  `https://maven.myriadclient.dev/releases`.
- Elytra Fly and Auto Armor fly with core's elytra engine (`Myriad.elytra()`): No Durability, chest swaps and the glide
  hold moved there unchanged, and five of this addon's mixins went with them. The Grim suite passes as before.
- **Elytra Fly**: Control gets **Lock Heading** (fly the way you faced when the glide opened, the camera free; now one
  setting shared with Altitude) and **Auto Walk** (fly forward without holding the key).
- **Tracers** draws every tracer line, so Range, Line Width and Fade With Distance apply to all of them: Storage and
  Blocks lose their own Tracers switch, and Tracers gets a Blocks option beside Storage.
- **No Render**: turning it on (or changing its blocks) no longer leaves the terrain undrawn until you move far away;
  chunks are rebuilt the way 26.2 expects, on vanilla and Sodium.
- **Auto Walk** (new, Movement): holds forward for you, also with chat or your inventory open. Holding back stops you.
- **Time of Day** (new, Render): locks the time of day you see (sky, sun, moon, stars, daylight) to Time, in ticks
  (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight). Only your view changes; the server's time doesn't. The moon
  keeps its phases.

## 0.2.20

Needs Myriad 0.2.11 (0.2.12 for Tracers and other world lines with a shader pack).

- **Elytra Fly** is one module again, with a Type: Recast, Altitude, or the new Control. Recast's settings, keybind and
  on/off state carry over (Altitude's and Elytra Control's settings don't: set them again under Elytra Fly). No
  Durability and Preserve are shared; each type's own settings show only while it's chosen.
- Control (new): fly the elytra with the movement keys, like a creative flight. Forward, back and the sides go along
  your heading at Speed, jump climbs and sneak descends at Vertical Speed, and with neither you hold your height (or
  drift down at Sink). Turning and stopping are instant; speed builds up as fast as NoCheatPlus's elytra check allows
  (about 1.8 blocks a second each tick). It stops at the edge of chunks that haven't loaded. Only offered where the
  server's anti-cheat doesn't simulate the glide (6b6t's NoCheatPlus, singleplayer): Grim (2b2t) sets it back, so there
  Type lists Recast and Altitude only.
- No Durability works the way the server allows. With Grim (2b2t) it swaps a chestplate on as before. Elsewhere (6b6t)
  that fails (a chestplate worn mid-glide is checked as walking; Recast couldn't take off), so it breaks the glide
  with two glide starts instead: one sent while gliding stops the glide, you hold still that tick, and the other starts
  it again; the server's 20-tick glide count breaks, no chestplate needed. A break the server doesn't confirm (both
  starts in one of its ticks: about one in five on 6b6t) is made again at once, holding still two ticks, as are the next
  20. Auto Armor's No Durability Elytra does the same. Grim flags this way, so 2b2t keeps the swap.
- Recast: the view no longer zooms in and out with the sprint the bounce holds and lets go at every touch.
- Recast on 6b6t: after a take-off or a Baritone hand-over, the hops could open the elytra three times each and fly
  slowly for a while. A start answering a stop the server sent before the last start had arrived (a round trip at
  ~125 ms ping) ended the glide that start had begun, and the two kept crossing. Away from Grim, a start now waits for
  the server's answer to the last one.
- Elytra Fly: a setback no longer turns the camera to the flight's own rotation (Recast's dive, straight down).
  6b6t's NoCheatPlus sends the last rotation you sent back with the setback; the camera now stays where you looked.
- Looking At (new HUD element, top centre on a fresh install): what the crosshair is on, in a card in the theme's
  colours (window background, border, rounding, blur). Blocks show their icon and name, the tool that harvests them
  (green if what you hold does, red if it doesn't and the block needs one), how long breaking takes with what you hold,
  a crop's growth and redstone power. Entities show their name in their ESP group colour, health (coloured by how much
  is left), armour, a player's ping and a horse's speed. It fades out when you look at nothing.
- Ping (new HUD element): your ping to the server.

## 0.2.19

Needs Myriad 0.2.11.

- Altitude: Lock Heading (off by default) keeps the flight going the way you faced when the glide started, to the
  degree rather than snapped to 45° like Recast, so the camera is free to look around. It lasts until you land (No
  Durability's mid-air glide restarts keep it) or turn it off.

## 0.2.18

Needs Myriad 0.2.11.

- Auto Armor no longer swaps a piece for a copy that's only less worn. With spare elytras carried (Chest: Elytra), it
  put on the freshest one at every point of wear, so each of them took its turn and all wore down together; now one
  is used until it's worn down to Preserve, then the freshest other. The same for armour.

## 0.2.17

Needs Myriad 0.2.11 (clicks that don't slow you while sprinting, and on 2b2t over held keys).

- Elytra Fly is now two modules, **Recast** and **Altitude**, each with its own No Durability and Preserve; turning one
  on turns the other off. Recast keeps Elytra Fly's saved settings, keybind and on/off state.
- Elytra Fly's Manual mode is now **Auto Armor**'s No Durability Elytra (in Chest Swap): while you fly an elytra
  yourself, it's swapped with a chestplate so the server barely wears it, and a glide dropped in mid-air is opened
  again. It stands down while Recast or Altitude is on: they swap the way their flight needs.
- An elytra one hit from breaking is never used to start a glide (the server refuses it, and Grim sets you back): the
  swaps pick another one, and Recast and Altitude stop rather than try.
- Stack Replenish tops up every low slot at once (as far as the packet budget goes), the first tick no screen is open,
  and while you sprint or (on 2b2t) walk or jump; sprinting never breaks for it. Main Hand, Off Hand and Hotbar choose
  which slots it refills (Offhand carries over as Off Hand). Recast no longer
  lets go of sprint mid-hop for a click unless the server judges held keys.
- The Grim test server is set like 2b2t where they're known to differ (clicks over held keys only flag), with
  `--strict` for current Grim; see tools/grim-test/README.
- Rocket Boost flies vanilla's way on a tick after you brushed a wall or the ground, or went through water, cobwebs,
  soul sand or the like: Grim carries on from what's left of last tick's movement then, which Rocket Boost can't know.

## 0.2.16

Needs Myriad 0.2.10 (`Breaking.Options.withWaitForServer`, `Inventory.mask`, `Inventory.clickWaiting`).

- Recast lets you use your inventory while it bounces. Your clicks used to wait for a moment the server takes them
  (Grim refuses clicks while you sprint, and Recast sprints for the jump boost), and meanwhile jump was let go of: the
  bounce slid along the ground and was set back. Now Recast stops sprinting for a tick in the air (where sprint changes
  nothing) whenever a click waits, and keeps its jumps: the click goes mid-hop, a tick or two later. On 2b2t, every
  click while bouncing went through mid-air, at 31-33 b/s with No Durability and no wear.
- Packet Mine leaves a block on screen until the server says it's gone (no ghost blocks when a finish is refused). With
  Myriad 0.2.10 its tool goes back in the same tick as the dig packet: no flags on the local Grim with its experimental
  checks on, and none on 2b2t.
- Packet Mine's queue shows again, drawn with the shader outlines (the boxes it drew sat on the blocks' own faces and
  lost to them): blocks waiting their turn faint and dotted, the block being mined a fill that thickens as it breaks,
  staying until the server says it's gone.
- Packet Mine with Auto Tool on: Auto Tool no longer picks a tool for the blocks Packet Mine mines (the breaking service
  does). Its own hold fought the service's, a slot switch each way every few ticks on 2b2t.
- Packet Mine sends one swing a tick at most: the click, the start and holding the button each added one (3-4 a click).
- Middle Click's rockets, experience bottles and pearls no longer swing your hand on screen; the server still gets the
  swing vanilla sends with a use.
- No Break is gone: the Preserve settings do its work where the gear is chosen.
  - Auto Tool's Preserve puts tools and weapons worn down to it aside for everyone: Packet Mine and other breaks pick
    another tool (brought in from the inventory if need be) and leave the worn one alone.
  - Auto Armor's Preserve (5%) swaps a worn-down piece for another, or takes it off when there's none (an elytra once
    you've landed), and other modules pass it over.
  - Elytra Fly has a Preserve too (5%): an elytra worn down to it is swapped for the freshest other one, in mid-air too,
    and No Durability's swaps leave it out.
- Elytra Fly Altitude, made to be left alone on 2b2t. With No Durability it flies a slower cycle (diving to 33-40 b/s,
  about 18-20 b/s on average) so chunks are in well before it reaches them, and swaps only where Grim knows every
  chunk on the way (Grim sets you back in a chunk you haven't answered the ping for, and No Durability holds those
  answers back): otherwise the elytra stays on until they're known, and a hold about to reach an unknown one is cut
  short. Each restart puts the elytra on first and starts only once the server has it on. A start 2b2t ignores (it
  does, now and then) no longer sets off a storm of setbacks: the first setback with no glide back from the server
  puts the elytra on, starts again, and swaps on once the server glides. On 2b2t: no setbacks, no wear, height held.
  Without No Durability (or without a chestplate) the elytra wears, and once it's down to what landing from where you
  are takes, a spare from your inventory goes on in mid-air. Altitude glides gently for a few seconds after a run of
  setbacks, slows down (climbing) while the chunks ahead haven't loaded, and when it can't go on (the last elytra worn
  out, the server setting you back over and over) comes down to land. New **After Landing** (Altitude): disconnect
  once landed (default), or stay there.
- The chestplate swaps (No Durability, Recast) no longer flicker on screen (Myriad's display masks): the hotbar slot
  or hand they go through keeps showing the chestplate, held still, without its name flashing up, and the armour bar,
  Armor panel, inventory screen and your model keep showing the elytra. Only the drawing; the inventory is as it is.
- No Durability puts the elytra back on with one inventory click when the server takes one (you're not moving or
  sprinting: Altitude, Manual), instead of using it from the hand. The chestplate still goes on by using it: Grim takes
  a click into its copy of your inventory at once and, with a chestplate there, sets back the glide the hold keeps up.
- Elytra Tweaks is split up. No Durability, and the catch of a glide dropped in mid-air, are part of Elytra Fly now,
  in every mode, with a new **Manual** mode for flying yourself (rockets) with just those. Rocket Boost is a module of
  its own. Set No Durability again in Elytra Fly, and turn Rocket Boost on, if you used them.
- Air Place's target block is drawn by Block Highlight when it's on (its look, where the block will go), in place of
  Air Place's own outline.
- Grim suite: No Break's tests are now Auto Tool, Auto Armor and Elytra Fly Preserve tests.

## 0.2.15

Needs Myriad 0.2.9 (for the ping 2b2t's tab list doesn't show, which Recast's chestplate swaps wait on).

- Elytra Fly Recast with No Durability: take-offs go like every other glide start, the elytra on, the start and the
  chestplate back on in one tick. They were left to vanilla (the elytra put on, its jump press starting the glide), so
  the server glided you on the elytra to the landing: on the first hop, and the first after eating, a Baritone
  hand-over or a setback. Those were all the wear 0.2.14 had left (7 points in about 146,000 blocks on 2b2t); with this,
  none in 53,000. The client glides from that start too, or it took off again the next tick (Grim's ElytraA).
- Recast and eating: Auto Eat eats while you bounce. Recast cancelled any food used while the chestplate was on, which
  with 0.2.14 was nearly always, so hunger ran down until you couldn't sprint (and bouncing slowed to a crawl). While you
  eat, or Auto Eat is about to (between one item and the next), no glides start and nothing is swapped, as each swap
  would stop the eating; the hops go on, slower, and running slow then doesn't count as being stuck.
- Recast: when Grim learns the glide stopped before the glide hold could keep it from it (its ping answered first),
  the glide starts again at once instead of waiting out the hop, which was a setback.
- Recast with Baritone: the hand-over is only done once you're at the lane's height. Baritone could leave you on top of
  what was in the way, just above its goal, and Recast took that as there, ran into it again and handed over again,
  every other tick, until you stepped in.
- Recast's notifications: one at a time, each replacing the last ("Lane blocked: Baritone is taking you past it" once,
  counted, instead of a stack of them on a broken stretch).
- Elytra Tweaks no longer opens the elytra again for a glide that drops where the landing can't hurt (Recast turned off
  mid-hop, a foot above the highway): it skimmed the ground for 30 blocks or more, wearing the elytra.

## 0.2.14

Needs Myriad 0.2.8.

- Elytra Fly Recast with Elytra Tweaks' No Durability no longer wears the elytra on 2b2t. 2b2t counts every tick the
  server glides you towards the wear, however short each glide and across landings (vanilla only wears a glide that
  lasts 20 ticks, which a hop never does), so bouncing wore about 2.5 durability every 1000 blocks there. Now the
  chestplate is worn through each hop: every glide start puts the elytra on, starts the glide and puts the chestplate
  back on in the same tick, and the glide hold keeps you gliding through the rest of the hop. A hop that runs long (off
  an edge) gets another start every 8 ticks. The server glides about 9% of the time instead of about 60%, at the same
  speed: about 46,000 blocks on 2b2t without a point of wear.

## 0.2.13

Needs Myriad 0.2.8.

- Offhand takes a totem from the hotbar first while you move, so it goes over with the swap-hands key at once; one
  from the main inventory waits a tick for a click Grim takes. The spare Hotbar Totem is refilled only while you aren't
  moving.
- Auto Armor no longer misses the still tick it asks for (its delay started on the request), so it equips every piece
  while you sprint.
- Elytra Fly Recast, Clear with Baritone: one hand-over per obstacle. Baritone gets a goal at the first clear spot past
  it and gets you there however it can, mining and placing (allowed for the hand-over, and with the building blocks you
  carry, obsidian say, added to the ones it builds with, all put back after). Once you're there you face along the lane
  again, at the pitch you had, and bouncing goes on. If Baritone stops short, or gets no closer for 15 s, the lane
  counts as blocked (If Blocked: wait or disconnect). Before, a stall stopped Baritone and gave it a new goal a few
  blocks on, up to three times (its goal flickering on and off), then fell back to mining and filling itself, and it
  left you looking wherever Baritone had turned you. Mining and filling itself is now only without Baritone.
- Air Place goes as far along your look as the server takes, up to the new Distance maximum of 6: farther than that,
  it comes back to the farthest space your look enters within reach (about 5 blocks ahead). It used to place exactly
  Distance away, capped at 4.5, and refused that whenever the space's centre was past 4.5, so it seemed to work or
  not depending on where you looked.
- Grim test suite: offhand sprinting (main inventory and hotbar), auto armor sprinting, stack replenish walking and
  kill aura walking; the dev console's `hold sprint` presses the sprint key itself, which ViaFabricPlus as 1.20.4
  needs to sprint. The arena is cleared in two fills: one was over the server's 32768-block limit and failed, so
  blocks from earlier tests stayed.

## 0.2.12

Needs Myriad 0.2.7.

- Elytra Fly's Obstacles is now Clear or Stop. Clear walks round what's in the lane first (Baritone, to the first clear
  spot past it), and without Baritone, or when it finds no way (or stalls for 10 s), mines through blocks and fills
  holes, placing only at floor level, never above your feet, sneaking up to the edge. Stop stops there. Saved Mine and
  Baritone settings become Clear.
- If Blocked (new): when the way can't be cleared (with Stop, anything in the lane), wait there or disconnect.
- Recast keeps the lane's height from where you first stand. Off the line or below that height (knocked or fallen off
  the highway), Baritone walks you back onto the lane at its height, building up if it must, before bouncing on.
- While Recast has you on foot (Baritone, mining, filling), a jump held into the air no longer opens the elytra.
- The flight physics use exactly the rotation the movement packet carries (core's continuous yaw and nudged pitch,
  through vanilla's own look maths), so Grim's simulation can't drift from it. Once something had turned you a long
  way silently (Baritone walking you round an obstacle), the plain lane angles drifted by a hair a tick and Grim
  flagged Simulation after the bounce went on.
- No Durability with several elytras: the same elytra keeps being used (the next swap takes from where the last one
  went), spares stay where they are, and Auto Armor leaves the chest slot alone while you're off the ground or Recast
  is on. With Chest set to Elytra it put the best elytra on over each chestplate swap, so each of your elytras wore in
  turn and they piled up in the hotbar.
- No Durability brings in what's missing (the chestplate, or the elytra back) only once its last swap has settled, and
  says so only when there really is none: the "needs a chestplate" warning came from reading the chest slot mid-swap.
  With no hotbar slot free (or holding a spare elytra) it says so once and doesn't click; it used to try every tick,
  letting go of your keys each time, which broke the bounce. Recast fetches it before it starts bouncing, and mid-bounce
  only at a still moment (Grim refuses clicks while you sprint).
- Auto Armor lets go of your keys for a tick before it clicks (Grim cancels clicks while you move).
- Suite: tests for spare elytras, a full hotbar, filling a hole, Disconnect, walking round with Baritone and getting
  back onto the lane; tests that teleport mark Grim's flags after the teleport (it was counted against the module).

## 0.2.11

Needs Myriad 0.2.7.

- No Render: Chat hides the chat however it's drawn. Other mods can replace the HUD's chat element and draw it
  themselves, which skipped the hook before; the open chat screen still shows it. Portal Overlay also stops the screen
  warp while you stand in a portal (it only hid the purple overlay; Nausea only stopped nausea's own warp).
- Recast with No Durability cuts a long glide sooner and in more places. On a diagonal highway the server misses
  far more of the landings (its glides ran 12 to 15 ticks, cut by the chestplate a few ticks short of the 20 that
  wear the elytra); the cut now comes 10 ticks after the glide's start, which leaves room for ping spikes and 2b2t's
  lag, and also runs while the bounce pauses after a setback (only with the ground close below). Measured as 1.20.4
  through 200 ms of ping with 80 ms of jitter on a 500-block diagonal: longest server glide 12 ticks (was 15, and 21
  once with Grim flags), no wear, no flags; at 14 TPS no wear either (one run had a brief Simulation flag).
- It also only swaps once the server has answered its last swap: with jitter the client can show for a tick what the
  server had before, and a swap made on that puts the client out of step with Grim (Simulation flags, setbacks).
- Tried and left out: wearing the chestplate between every start, so the server never glides more than a moment (as
  some 2b2t clients do). Under 80 ms of jitter it flags Grim's Simulation where the bounce touches the ground.

## 0.2.10

Needs Myriad 0.2.7 (`Inventory.spare`, which No Break uses).

- No Break (new): armour, elytras and the tools and weapons in your hands are swapped out before they break, so they can
  be mended later. Another piece of the same kind with more uses left goes on (another elytra in mid-air, another
  pickaxe for the pickaxe); with none, armour comes off and a tool goes into the inventory, while the elytra you're
  flying with stays on until you land. Armor Uses (20) and Tool Uses (10) set when. What it puts away is left alone by
  Auto Armor, Auto Tool, Elytra Tweaks and core's tool picking for breaks (Packet Mine, Recast's mining).
- Elytra Fly's Recast with No Durability no longer wears the elytra with ping. The server can miss a landing (its
  packet handled in the same server tick as the next hop's, with jitter or low TPS) and glide on through the next hop;
  the cut that ends a long glide with the chestplate counted from your last touchdown, so it never came. It now counts
  from the glide's start until the server stops it. Measured as 1.20.4 through 200 ms of ping with 80 ms of jitter:
  30 s of bouncing wore the elytra by 2 (chestplate in the hotbar) and 5 (chestplate only in the inventory) before,
  0 after, also at 14 TPS.
- Recast with No Durability brings a chestplate from the inventory into the hotbar, as Elytra Tweaks does, and says so
  if there's none.
- Recast under a ceiling too low to hop (a two-high tunnel, a roof over the lane) walks along the lane, sprinting, until
  there's room overhead, then bounces on, in every Obstacles mode. Before, Stop waited forever, Mine dug the ceiling out
  until it had about 34 clear blocks ahead, and Baritone could walk you to such a spot and get stuck there; Baritone's
  spot past an obstacle now has room to hop when there's one.
- No Render: Chat hides chat messages on screen; they still show while chat is open.
- Dev client: ViaFabricPlus, joining servers as 1.20.4 like most 2b2t players (`-Pvia=native` for 26.2).
- Grim test server: ViaBackwards, so 1.20.4 clients can join. Suite: the elytra wear checks read the damage the server
  stores (they read nothing and always passed); tests for No Break, Recast's wear and a low ceiling; Scaffold judged on
  the path it walked (slower as 1.20.4); Elytra Tweaks' No Durability test flies higher, clear of the hills its fall
  guard rightly waits for.

## 0.2.9

Needs Myriad 0.2.6.

- Auto Eat picks food by why it eats. Low health eats Health Foods first (new setting; golden apples by default), the
  strongest first: an enchanted golden apple's absorption and regeneration before a plain one. Hunger eats the most
  saturating ordinary food (golden carrots before steak) and, with Save Health Foods (on by default), golden apples
  only when there's nothing else, the plain ones first. Food is found anywhere in the inventory, not just the hotbar:
  it's borrowed into the hotbar and put back where it was afterwards.
- Grim suite: an Auto Eat food choice test.

## 0.2.8

Needs Myriad 0.2.6.

- Auto Disconnect: Beds and Anchors leave when one could kill you (with Myriad 0.2.6, which counts the exploding block
  as gone: before, its own block shielded you in the maths and they never left). Falls and Void are gone: the server
  keeps you where you were, falling, so you landed as you rejoined (on the Grim test server it left 25 blocks into a
  fall, and the rejoin died two seconds later).
- Grim suite: an Auto Disconnect test (low health, a charged anchor, rejoining after each).

## 0.2.7

Needs Myriad 0.2.5.

- Block Highlight draws on a layer under other highlights, so the block you look at no longer hides Storage's (and
  other modules') highlights behind it on screen: with Through Walls on, a wall you looked at hid every chest behind
  it. Storage leaves out the block Block Highlight is on, which shows Block Highlight's look; the other half of a
  double chest keeps Storage's.

## 0.2.6

Needs Myriad 0.2.4 (it sends Elytra Fly's held pitch again after a setback; see its changelog).

- Elytra Fly's Recast is steadier left alone:
  - The highway is fixed when you turn it on (the nearest 45° to where you face, through the middle of your block),
    so looking around never steers it, and drifting off that line (a setback, the way round an obstacle) turns the
    flight a little back onto it: from 0.4 blocks off, back on the line within a second.
  - It watches far enough ahead to stop in time, growing with speed (about 33 blocks at 42 bps; it was 3.5, less than
    two ticks of travel), for blocks in the way, cobwebs, holes at least two deep, and chunks that haven't loaded
    (it waits for those).
  - It stops the way a player would: no more jumps, then it lands and slides to a halt along the lane. It used to
    zero your speed mid-air, which Grim's movement simulation doesn't expect, and the landing tick steered towards the
    camera.
  - Mine walks up to the block and mines it with the breaking service's Grim-safe packet mining; with Baritone, it
    walks to the first spot past the obstacle with a floor and room to bounce, and bounces on once back on the line.
    Holes and unbreakable blocks go to Baritone when it's installed, and otherwise stop and wait.
  - Stuck (no progress for two seconds) or set back three times in five seconds, it walks past with Baritone or
    turns off with a warning instead of trying again forever.
  - Two new Grim suite tests: a straight 10 s run (272 blocks, on the line) and an ender chest plus a hole.
- Elytra Tweaks' No Durability no longer lands you with fall damage you didn't earn. While it swaps, the server
  takes you as falling, not gliding, and counts every block you come down (only rising clears it): gliding down to
  land afterwards cost all of it, up to a lethal fall from a gentle landing (a test flight banked 78 blocks; Grim test
  server, no lag). It now keeps that count as the server does, and once it's past a safe fall it looks ahead far
  enough (below you and along your flight, for a round trip plus the second or so the server needs to let go of it)
  to put the elytra back on in time; the landing test now touches down at full health. Swaps also stop for rising
  ground, walls and trees ahead, not only for the ground straight below, and stopping never drops you out of the
  glide in mid-air (it waits the one tick until the glide can start again). New suite test: a No Durability landing.
- Elytra flight can be left alone (tested at 2b2t-like ping, 100 ms each way, on the Grim test server):
  - Altitude no longer gets set back every round trip after a single setback (with Myriad 0.2.4, which sends the held
    pitch again after one); 300 ticks at that ping, no flags.
  - No Durability, stopping in mid-air, waits for the server's stop for the last swap (still on its way at that
    ping) and starts the glide again, instead of letting that stop drop you out of the glide 15 blocks up with the
    whole fall still counted: the landing test went from 3 deaths in 3 to 14 safe landings in 15 (the one death
    was in a run of the old test, which put you above its floor before building it). It also stops
    swapping before chunks you'd fly into have loaded.
  - Elytra Tweaks catches a glide that drops in mid-air for any other reason (no ground, water, ladder or vehicle):
    it opens the elytra again at once, as a player would, and says so in chat.
  - Altitude watches the ground ahead (160 blocks, from loaded chunks) and raises its cruise height to stay 24 blocks
    above it; ground closer than it can clear that way makes it pull up and keep climbing, so a cliff it can't get
    over ends in a slow stall against it rather than a crash at full speed.
  - Altitude lands while the elytra can still take it: once it has fewer uses left than the way down takes (at a
    conservative 3 blocks a second, plus 30), it comes down at 20° and levels out near the ground. From 225 blocks
    up with 102 uses left it landed unhurt with 50 to spare.
  - Two more suite tests (a No Durability landing, an elytra wearing out in Altitude); the landing test now fails if
    you died and respawned.
- Full Bright works with shader packs in Gamma mode too: packs light the world themselves and ignored the brightened
  lightmap, so caves stayed dark. They're now told you have night vision, which they brighten by, without a real
  effect on you; vanilla's look is unchanged.

## 0.2.5

Needs Myriad 0.2.3.

- Free Look's scroll distance works next to Boze, which sets the third-person camera's distance itself and used to
  keep it at 4 blocks.

## 0.2.4

Needs Myriad 0.2.3.

- Free Look: the scroll wheel moves the camera nearer or further (1 to 30 blocks, easing between steps; it starts at
  vanilla's 4 each time), and the camera orbits the middle of your player instead of its eyes. Blocks still pull the
  camera in so it never ends up inside a wall.

## 0.2.3

Needs Myriad 0.2.3.

- Blocks' Outline mode keeps its silhouettes on the GPU per chunk (only the outside faces of each clump), so even a
  very common block costs nothing per frame. Before, every block was sent again each frame, and picking stone dropped
  the frame rate badly.
- Blocks' Block Colors gives each block one colour (its default look's): a bed's head and foot (white and the dye on
  a map) or a log's end and bark no longer split into separately outlined shapes.
- Block Highlight no longer flickers against Storage's or Blocks' highlight of the same block (an opening chest).
- ESP's outlines show over Storage's and Blocks' (core draws entities above shapes), so players behind a highlighted
  chest wall stay visible.

## 0.2.2

Needs Myriad 0.2.2.

- Block Highlight is always its own outline around just the block you're looking at (half a double chest, one end of a
  bed), drawn over Blocks' and Storage's highlights. Before, it merged with them where they touched and lost that edge.

## 0.2.1

Needs Myriad 0.2.1.

- Blocks has a Mode: Boxes (as before) or Outline, the shader outline ESP and Storage have, around each block's
  shape; touching blocks share one outline, so a vein or a portal is one shape.
- Block Highlight (Render): your own look for the block you're looking at, in place of vanilla's thin black outline.
  Outline (the shader outline, with glow, fill and gradient) or Box (fill, lines or both), following the block's real
  shape, and only where it's visible unless you turn on Through Walls.

## 0.2.0

Needs Myriad 0.2.0 (highlights).

- ESP has an Outline mode: a shader outline around each entity's exact shape (armour and held items included), with
  Glow, Fill (none, solid or a dot grid), Gradient and Through Walls. Box options are hidden in that mode.
- Storage has a Mode: Boxes (as before) or Outline, the same shader outline around every container shown; touching
  containers share one outline, and chest minecarts, chest boats and pack animals are outlined by their shape.
- The dot fill's default grid is half the size (2.5 px apart, 1 px dots).
- Nametags hides the vanilla name tag (and the score under it) on players and mobs it draws its own tag for; the
  vanilla username showed above Myriad's before (fixed in core).

## 0.1.2

- No Render only listens for blocks and entities while it hides some (a block list, Vines, an entity list or Dead
  Entities). Before, its block hook ran for every block of every chunk the game rebuilt, tens of thousands of calls a
  second while moving, even with nothing to hide.

## 0.1.1

The first release from its own repository, replacing 0.1.0 (withdrawn): the stock modules (Combat, Movement,
Player, Render, World) and HUD elements, Grim-safe on 2b2t by default. Needs Myriad 0.1.0 or later.

- Velocity's Explosions setting works: explosion knockback was never reaching the module.
- Zoom eases over a set time (Duration, in ms; 0 snaps) instead of Smooth and Speed, and zooms at an even rate
  through the ease. A saved Smooth off becomes Duration 0.
- Auto Eat eats at 10 health or 16 hunger by default (18 or more keeps natural regeneration going), and gives up on
  a bite that runs past the food's own eating time.
- Elytra Fly finds obstacles by sweeping your hitbox along the lane, so a block only counts if you'd actually hit it.
- Armor HUD: Hide Undamaged and Text Scale replace Show Full and Percent Scale.
- `contact.sources` points at this repository, so Myriad's updater finds Essentials releases.
