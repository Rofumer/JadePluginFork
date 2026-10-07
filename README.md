# Jade Plugin for Paper 26.3

Paper 26.3 server plugin that answers [Jade](https://github.com/Snownee/Jade) client requests, so players using the
Jade mod on Fabric get server-side info, **including the contents of containers**.

This is a modified version of [Jade Plugin](https://modrinth.com/plugin/jade-plugin) 0.5.0 by **Jonyboylovespie**,
licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). No source code was published for
the original, so this version was reconstructed from the decompiled 0.5.0 jar, then rewritten for Paper 26.3. The same
license applies to this version.

## Changes from 0.5.0
- New `minecraft:item_storage` provider. It shows the contents of chests (including double chests), barrels, shulker
  boxes, hoppers, dispensers, brewing stands and other containers. It also works for the viewer's own ender chest,
  chest/hopper minecarts, chest boats and horse/donkey/llama inventories. Like Jade, it shows "loot not generated" or
  "locked" instead of the contents when that applies, and supports the client's sort option and counts above 99.
- Requests are decoded with Minecraft's own stream codecs, matching Jade's `SyncData` layout. The old byte-scanning
  heuristic could fail whenever the client attached an item to the request.
- The plugin now refuses or limits requests the same way Jade's server side does: a reach check (interaction range
  plus `max-position-deviation`), no data for unloaded chunks (no chunk loading on request), a per-player request
  interval, and command block contents only for players who can use command blocks.
- Fixed payload formats: absorption was written little-endian, the pet owner was sent as a JSON string instead of a
  Component, and the trial spawner cooldown read a method that no longer exists.
- Replies are sent as raw custom payloads, so they no longer depend on `CraftPlayer.addChannel`, which was removed.
- Uses paperweight-userdev (Mojang-mapped NMS) instead of reflection.

## Config (`plugins/JadePlugin/config.yml`)
| Key | Default | Meaning |
|---|---|---|
| `max-position-deviation` | `21` | Extra blocks on top of the interaction range for which requests are answered |
| `request-interval-ms` | `50` | Minimal interval between requests per player |
| `item-storage.enabled` | `true` | Enables container contents |

Permission `jadeplugin.itemstorage` (default: everyone) controls who can see container contents.

**Note on protected areas:** like Jade itself, the plugin does not know about region/claim plugins (WorldGuard,
GriefPrevention, ...). Anybody in range can see what is inside a container. Revoke `jadeplugin.itemstorage` or turn off
`item-storage.enabled` if that is not acceptable on your server.

## Build
Requires JDK 25 (Gradle can provision it).
```
./gradlew build          # build/libs/JadePlugin-<version>+26.3.jar
```

## Self test
`./gradlew selftestJar` builds `build/selftest/JadeSelfTest.jar`. This is a second plugin that, inside a running
Paper 26.3 server, sends this plugin's payloads through the codecs from the real Jade 26.3 jar: the handshake, both
request packets, `item_storage` for several kinds of container, and some vanilla providers. Put both jars into
`plugins/` and start the server with `-Djade.selftest.shutdown=true`; the log ends with
`[JadeSelfTest] RESULT: N passed, 0 failed`. The Jade jar is taken from Modrinth Maven
(`maven.modrinth:jade:26.3.5+fabric`).
