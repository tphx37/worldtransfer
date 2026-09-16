# World Transfer (LAN Host Handoff)

A Fabric mod for Minecraft 26.3 that hands a LAN world to a different player,
without anyone's inventory, position, or stats getting mixed up in the
process.

Normally the only way to "give" someone your world is to send them the whole
save folder as-is - which makes *them* spawn as *you*, with your inventory,
your XP, your position. World Transfer instead produces a ZIP where the
recipient becomes the new host but keeps being themselves, and everyone else
who rejoins keeps being themselves too.

## Usage

The only requirements are Minecraft 26.3+, Fabric Loader 0.19.5+, and its Fabric API.

0. The mod only
needs to be on the client that's currently hosting and on whoever's about to
become the new host, but since anyone might get chosen, **it's simplest to
just have it on every client.**
1. You host a LAN world with the mod (and Fabric API) installed.
2. Choose **Transfer world ownership** in the pause menu and pick a connected
   player.
3. The mod builds a ZIP of the current world, live in memory (not a copy of
   whatever was last written to disk) and saves it to your Desktop or your
   `.minecraft` folder (can be chosen).
4. You send that ZIP to the player you picked.
5. They unzip it into their `saves` folder and open the world from the
   singleplayer list.
6. They're the host now, with their own inventory, health, XP, armor, and
   position. Everyone else who joins their LAN game keeps their
   own data too, the same way it always works in vanilla.

Your original world folder is never modified. The transfer only touches the
copy inside the ZIP.


## Building from source

Requires Java 25+, Fabric Loader 0.19.5+, and Fabric API, on Minecraft 26.3+.

```powershell
./gradlew.bat build
```

The compiled jar lands in `build/libs/world-transfer-<version>.jar`. Drop it
in `mods/` folder of your instance for every player who will take part in a transfer or just send it to everyone, as explained in Usage step 0.
## Advanced settings

The transfer screen has an **Advanced settings** panel with toggles for:

- **Player data**: whether player files (inventory, position, health, XP,
  ender chest, etc.) are included at all. Turn this off and everyone spawns
  fresh in the transferred world.
- **Advancements**, **Entities**, **World data**: include or drop the
  corresponding save folders.
- **Save to Desktop / Minecraft folder**: where the ZIP is written.


## Known limitations

- **Chunk state, not just player state, is a snapshot.** The ZIP is built
  from the world as it is at the moment of transfer (after a forced save),
  but anything that happens on the old host's client after that point isn't
  reflected in the file the new host receives.
- **No network transport.** The mod produces a file; sending it to the other
  player is still on you (in development, see below).
- **Same-session UUID assumption.** The name-based fallback only helps if
  the player's *name* is unchanged between sessions. It won't help two
  different real people who happen to share a name.
- **Large worlds mean large ZIPs.** There's no delta/incremental transfer -
  every included folder is zipped in full each time.
- **No conflict handling for simultaneous transfers.** The mod assumes one
  transfer completes (i.e., one ZIP gets used) before another is started
  from the same world.

## Partially developed but not working

- **Direct transfer over LAN**, sending the archive to the target player's
  client automatically instead of producing a file to hand off manually.
- **Incremental exports** - ship only the region files / player files that
  changed since the last transfer, instead of the whole world each time.
- **Multi-hop history**, so a world can show who's hosted it and when,
  rather than only knowing the current and previous host.
- **A confirmation/receipt step**, where the new host's client verifies the
  ZIP's `world-transfer.json` marker matches who they expect before treating
  the save as valid, to guard against being handed the wrong file.
- **Cross-version safety checks**, since this whole mechanism is built
  around how the current Minecraft version stores world ownership - a
  future Mojang change to that format would need a matching update here.

## Other
<details>
  <summary><b>Note for developers</b></summary>

  > 
  > Minecraft doesn't store "who owns this save" in an obvious place. In modern versions it's a single field, `Data.singleplayer_uuid`, inside `level.dat`. When you open a save, the game checks whether your local profile name matches the save's recorded owner; if it does, it loads *that UUID's* player file regardless of your own UUID. Any approach that doesn't touch `singleplayer_uuid` silently fails: the new host opens the world and gets the old host's data instead of their own, and if the two players' UUIDs differ, whoever spawns can even end up holding the *other* player's identity, since the UUID stored inside the player file overrides the one the game already knows about that client.
  > 
  > So this version already has two parts working together:
  > 
  > - **Ownership**: the exported `level.dat` has `singleplayer_uuid` pointing at the chosen player, so Minecraft's own loading logic hands them their own file automatically. If something isn't right another "Tranfer waiting" menu will show.
  > - **Identity**: every player file written into the ZIP has its internal `UUID` tag stripped before being saved, so loading it can't overwrite a player's actual UUID with whoever's file it happens to be.
  > 
  > A secondary problem: two people's Minecraft accounts aren't guaranteed to produce the same UUID between an online (purchased/licensed) session and an offline/LAN session. If someone's UUID differs from the one recorded when the ZIP was made, they'd have no `playerdata` file waiting for them at all. The mod covers this by also saving each player's data under their name, in a small `world-transfer/players/` folder inside the ZIP; if a player joins the new world and Minecraft can't find playerdata for their current UUID, the mod restores it from their name instead, once, and then gets out of the way.

</details>

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
If you use this code or parts of it in your project, providing a link back to this repository or mentioning me in your credits is highly appreciated!


 