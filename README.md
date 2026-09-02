<p align="center">
  <img src="web/icon-256.png" width="128" alt="Realmscry">
</p>

<h1 align="center">Realmscry</h1>

<p align="center">
  Scry your <a href="https://www.realmofthemadgod.com/">Realm of the Mad God</a> sessions —
  a standalone, self-updating loot &amp; damage tracker with a clean dark web UI.<br>
  One jar. Double-click. Done.
</p>

---

## What it does

- **Recent Loot** — live feed of your white / orange / red bag drops with item
  and bag sprites, **shiny detection** (glow, sparkle, popup + sound cue) and
  **enchantment-slot indicators** (the 1–4 diamond clusters, just like in
  game). Every drop is kept in a persistent, scrollable history with day
  dividers.
- **Last Boss Killed** — damage leaderboard for the boss you just killed:
  top-5 players with avatars and their equipped loadouts, plus your own rank
  highlighted even when you're not in the top 5.
- **Self-updating** — checks this repo's releases at launch and updates
  itself. Install once, get every new feature automatically.

Everything runs locally: Realmscry passively reads the game's network traffic
on your own machine (the same approach as
[RealmShark](https://github.com/X-com/RealmShark)). It never modifies game
files, injects into the client, or sends anything anywhere — the UI is served
only to `localhost`.

## Install (once)

1. Install the packet-capture driver *(Windows only — default settings are
   fine)*:
   - **Npcap for Windows:** <https://npcap.com/>
2. Install **Java 25**:
   - **Java for Windows:** [jdk-25_windows-x64_bin.msi](https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.msi)
   - **Java for Mac (Apple Silicon):** [jdk-25_macos-aarch64_bin.dmg](https://download.oracle.com/java/25/latest/jdk-25_macos-aarch64_bin.dmg)
   - **Java for Mac (Intel):** [jdk-25_macos-x64_bin.dmg](https://download.oracle.com/java/25/latest/jdk-25_macos-x64_bin.dmg)
   - Other platforms / all versions: [Oracle JDK 25 downloads](https://www.oracle.com/java/technologies/javase/jdk25-archive-downloads.html)
3. Download **`Realmscry.jar`** from the
   [latest release](../../releases/latest) and put it in its own folder
   (it creates an `assets/` folder and a loot-history file next to itself).
4. Double-click `Realmscry.jar`. A small launcher window appears; hit
   **Open Tracker UI** (or browse to <http://localhost:8420>).

> **Mac note:** macOS ships its own capture library (no Npcap needed), but
> reading network traffic requires extra permissions — run
> `sudo java -jar Realmscry.jar` from Terminal. Windows is the
> primary/tested platform.

Play. Bags and boss kills show up as they happen.

## Good to know

- Game updates are handled automatically: when RotMG patches, Realmscry
  re-extracts item/enemy data and sprites from your installed game client on
  the next launch.
- Close the launcher window to stop the tracker.
- Options via system properties, e.g.
  `java -Dtracker.volume=0 -jar Realmscry.jar`:
  `tracker.port` (default 8420), `tracker.volume` (0–100, 0 mutes),
  `tracker.nogui`, `tracker.noupdate`.
- `http://localhost:8420/debug` shows live diagnostics if something seems off.

## Build from source

No build system — plain `javac` against the bundled Tomato jar (see
`build.bat`). The packaged sprite/ID assets are extracted from your own
installed game client at first run.

## Credits

Built on the [RealmShark](https://github.com/X-com/RealmShark) packet
library (Tomato). Not affiliated with DECA Games.
