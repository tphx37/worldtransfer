# World Transfer (LAN Host Handoff)

A Fabric and NeoForge mod for Minecraft 26.3 that hands a LAN world to a different player,
with everyone's inventory, position, and stats without it getting mixed up in the
process.

Normally the only way to "give" someone your world is to send them the whole
save folder as-is - which makes *them* spawn as *you*, with your inventory,
your XP, your position. World Transfer instead hands ownership to the
recipient while they keep being themselves, and everyone else who rejoins
keeps being themselves too.



## Usage

Requirements: Minecraft 26.3+, either Fabric Loader 0.19.5+ with Fabric API or
NeoForge 26.3.0.7-beta, and Java 25+.

The mod only needs to be on the client that's currently hosting and on 
whoever's about to become the new host, but since anyone might get chosen, 
**it's simplest to just have it on every client.**

1. Host a LAN world with the mod (and Fabric API or NeoForge) installed.
2. Open the pause menu and click the **WT** button next to *Save and
   Quit*.
3. Pick a connected player from the list, then choose how to hand it over:
   - **Send to \<name\>** - sends the world straight over the existing LAN
     connection. Requires the recipient to have the mod installed too; if
     they don't, you'll be told and can fall back to a ZIP.
   - **ZIP** - builds an archive (from the world as it is in that instant,
     not a copy of whatever was last written to disk) and saves it to your
     Desktop or your `.minecraft` folder. Send it to them yourself and have
     them unzip it into their `saves` folder.
4. Whichever route you used, they end up as the host, with their own
   inventory, health, XP, armor, and position. Everyone else who joins their
   LAN game keeps their own data too, the same way it always works in
   vanilla.

A direct send that's already been done once for a given world is
**incremental** afterward: the receiving client checks what it already has
(matched by a stable ID stored with the save, not the folder name) and only
asks for what actually changed, rather than re-sending everything.

Your original world folder is never modified. The transfer only touches the
copy that gets sent or zipped.

### Receiving a transfer

- **Direct send**: an "Incoming world transfer" prompt pops up automatically
  with Accept/Decline. If you're mid-transfer already, or just want to check
  on it again, it's also listed inside the same **WT** menu, alongside
  the outgoing options.
- **ZIP**: unzip it into `saves` and open it from the singleplayer list like
  any other world.

Either way, opening the transferred world for the first time shows a
message naming who handed it over to you and when - or a warning if the
save doesn't actually name you as the intended recipient, which usually
means the wrong ZIP got passed around.

## Advanced settings

The transfer screen has an **Advanced settings** panel with toggles for:

- **Player data**: whether player files (inventory, position, health, XP,
  ender chest, etc.) are included at all. Turn this off and everyone spawns
  fresh in the transferred world.
- **Advancements**, **Entities**, **World data**: include or drop the
  corresponding save folders.
- **Carry over cheats**: whether the copy keeps the world's cheats flag.
  **Off by default.** With it off, the new host's own LAN game starts with
  guest command access disabled for whoever joins them, the same as
  starting a fresh world normally would; turn it on only if you specifically
  want that carried over. Note that op status itself never transfers either
  way - it's tied to the game install, not the save.
- **Save to Desktop / Minecraft folder**: where a ZIP is written (doesn't
  apply to a direct send).

## Ownership history

Each world keeps a small log of who's hosted it and when, that travels with
the save through every hand-off. It's viewable from the same **WT** menu.

## Known limitations

- **Chunk state, not just player state, is a snapshot.** Both routes build
  from the world as it is at the moment of transfer (after a forced save),
  but anything that happens on the old host's client after that point isn't
  reflected in what the new host receives.
- **Same-session UUID assumption.** The name-based fallback (for when a
  player's UUID differs between sessions, e.g. switching online/offline)
  only helps if the player's *name* is unchanged. It won't help two
  different real people who happen to share a name.
- **No resume for an interrupted direct send.** If the connection drops
  mid-transfer, it has to be started over. But an interrupted or failed
  apply does not corrupt an existing incremental copy.
- **One direct transfer at a time.** The sender rejects a second direct
  transfer until the first one completes, avoiding competing snapshots and
  overlapping writes to the same receive slot.
- **No block-level diffs.** Incremental sync compares whole files (so a
  changed region file re-sends in full, not just the changed chunks inside
  it).
- **Large first-time ZIPs.** The ZIP route has no incremental option at
 all - every included folder is zipped in full.

## Partially developed but not working

- **~~Automatic reconnect~~** - having the old host's client automatically
  rejoin the new host's LAN game once the transfer finishes, instead of
  needing to rediscover it manually.
- **~~Signed markers~~**, to verify a ZIP's provenance beyond just trusting the marker inside it.

Automatic reconnect and signed markers remain intentionally disabled. Minecraft
does not expose the new host's LAN address to the old host after handoff, and a
signature needs a key trusted out-of-band. Embedding a key in the ZIP would only
allow an attacker to replace both the marker and its key, meaning that the current marker is not an authenticity guarantee.

## Other
<details>
  <summary><b>Note for developers</b></summary>

  > 
  > Minecraft doesn't store "who owns this save" in an obvious place. In modern versions it's a single field, `Data.singleplayer_uuid`, inside `level.dat`. When you open a save, the game checks whether your local profile name matches the save's recorded owner; if it does, it loads *that UUID's* player file regardless of your own UUID. Any approach that doesn't touch `singleplayer_uuid` silently fails: the new host opens the world and gets the old host's data instead of their own, and if the two players' UUIDs differ, whoever spawns can even end up holding the *other* player's identity, since the UUID stored inside the player file overrides the one the game already knows about that client.
  > 
  > So this mod has three parts working together:
  > 
  > - **Ownership**: the exported `level.dat` has `singleplayer_uuid` pointing at the chosen player, so Minecraft's own loading logic hands them their own file automatically.
  > - **Identity**: every player file written out has its internal `UUID` tag stripped before being saved, so loading it can't overwrite a player's actual UUID with whoever's file it happens to be.
  > - **Permissions**: `Data.allowCommands` in `level.dat` is what actually grants guests of a LAN game command access (not `ops.json`, which never leaves the machine it's on) - so it's stripped from the copy unless "Carry over cheats" is explicitly turned on.
  > 
  > A secondary problem: two people's Minecraft accounts aren't guaranteed to produce the same UUID between an online (purchased/licensed) session and an offline/LAN session. If someone's UUID differs from the one recorded when the copy was made, they'd have no `playerdata` file waiting for them at all. The mod covers this by also saving each player's data under their name, in a small `world-transfer/players/` folder inside the save; if a player joins and Minecraft can't find playerdata for their current UUID, the mod restores it from their name instead, once, and then gets out of the way.
  > 
  > The direct-send route reuses the same export pipeline as the ZIP route (same `level.dat` rewrite, same stripped player files) but streams it over the existing LAN connection instead of writing an archive to disk. The receiving side hashes whatever local copy it already has of that world (matched by a stable ID file, not the folder name) and only requests the files that actually differ, which is what makes a repeat transfer of the same world fast.

</details>

## Building from source

The project is split into three Gradle modules:

- `common` contains loader-independent file indexing, hashing, marker,
  ownership-history, and network-payload definitions.
- `fabric` contains the Fabric initializer, Fabric API networking and lifecycle
  registrations, client screens, and Fabric-only mixins.
- `neoforge` contains the NeoForge `@Mod` constructor, event-listener boundary,
  shared-payload registration, capability-registration boundary, metadata, and
  the NeoForge implementation surface. NeoForge-specific lifecycle, command,
  payload, client-event, and capability registration lives there without
  changing `common`. Both loader artifacts now use the same transfer engine,
  incremental receiver, payload definitions, screens, ZIP export, and ownership
  handling.

Build both loader targets from the repository root:

```powershell
./gradlew.bat build
```

The loader jars are written to `fabric/build/libs/` and
`neoforge/build/libs/`. The shared library is written to `common/build/libs/`
and is included as a project dependency by each loader module.


## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
If you use this code or parts of it in your project, providing a link back to this repository or mentioning me in your credits is highly appreciated!
