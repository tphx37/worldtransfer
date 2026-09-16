package com.worldtransfer;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hands a LAN world to another player.
 *
 * <p>The important detail for Minecraft 26.x: the "who is the host" information in a save is
 * {@code level.dat -> Data.singleplayer_uuid}. When a world is opened in singleplayer the game asks
 * {@code PlayerList.loadPlayerData}, which - for the owner of the save - ignores the joining player's own
 * UUID and loads {@code playerdata/<singleplayer_uuid>.dat}. The legacy {@code Data.Player} tag is no longer
 * read at all. So the whole transfer is one change: point {@code singleplayer_uuid} at the new host and make
 * sure that player's own .dat file is in the ZIP. Everybody else keeps their own UUID-named file.</p>
 */
public class WorldTransfer implements ModInitializer {
    public static final String MOD_ID = "worldtransfer";
    public static final String MARKER_FILE = "world-transfer.json";
    public static final String SNAPSHOT_DIR = "world-transfer";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern PARTICIPANT_NAMES = Pattern.compile("\"participants\"\\s*:\\s*\\[([^]]*)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Players already handled by the name fallback in this session. */
    private static final Set<UUID> FALLBACK_DONE = new HashSet<>();
    /** Participant names seen online since this world was opened. */
    private static final Set<String> SEEN_NAMES = new HashSet<>();

    private record Snapshot(String name, CompoundTag data) {
    }

    @Override
    public void onInitialize() {
        TransferNet.register();
        ServerTickEvents.END_SERVER_TICK.register(WorldTransfer::tickNameFallback);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var worldData = Commands.argument("worldData", BoolArgumentType.bool())
                .executes(context -> prepareTransfer(
                    context.getSource().getServer(),
                    EntityArgument.getPlayer(context, "player"),
                    context.getSource().getPlayer(),
                    StringArgumentType.getString(context, "destination"),
                    BoolArgumentType.getBool(context, "playerData"),
                    BoolArgumentType.getBool(context, "advancements"),
                    BoolArgumentType.getBool(context, "entities"),
                    BoolArgumentType.getBool(context, "worldData")));
            var entities = Commands.argument("entities", BoolArgumentType.bool()).then(worldData);
            var advancements = Commands.argument("advancements", BoolArgumentType.bool()).then(entities);
            var playerData = Commands.argument("playerData", BoolArgumentType.bool()).then(advancements);
            var destination = Commands.argument("destination", StringArgumentType.word()).then(playerData);
            var player = Commands.argument("player", EntityArgument.player()).then(destination);
            dispatcher.register(Commands.literal("worldtransfer").then(Commands.literal("prepare").then(player)));
        });
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private static int prepareTransfer(MinecraftServer server, ServerPlayer target, ServerPlayer source,
                                       String destination, boolean includePlayerData, boolean includeAdvancements,
                                       boolean includeEntities, boolean includeWorldData) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        try {
            // Flush everything to disk first, so the copied chunks/level data match what people just played.
            server.getPlayerList().saveAll();
            server.saveEverything(true, true, true);

            Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                snapshots.put(online.getUUID(),
                    new Snapshot(online.getGameProfile().name(), serializePlayer(server, online)));
            }

            Path archive = createArchive(worldRoot, destination, snapshots, target.getUUID(),
                target.getGameProfile().name(), source == null ? "server" : source.getGameProfile().name(),
                includePlayerData, includeAdvancements, includeEntities, includeWorldData);

            if (source != null) {
                source.sendSystemMessage(Component.literal(
                    "Transfer ZIP created: " + archive.toAbsolutePath() + ". Send it to "
                        + target.getGameProfile().name() + "; your own world is unchanged."));
            }
            target.sendSystemMessage(Component.literal(
                "A transfer ZIP with you as the new host was created. Unzip it into your saves folder and open it."));
            LOGGER.info("World transfer archive written to {} (new host {})", archive, target.getUUID());
            return 1;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("World transfer failed", exception);
            if (source != null) {
                source.sendSystemMessage(Component.literal("World transfer failed: " + exception.getMessage()));
            }
            return 0;
        }
    }

    private static Path createArchive(Path worldRoot, String destinationChoice, Map<UUID, Snapshot> snapshots,
                                      UUID targetUuid, String targetName, String sourceName,
                                      boolean includePlayerData, boolean includeAdvancements,
                                      boolean includeEntities, boolean includeWorldData) throws IOException {
        Path destination = findExportDirectory(worldRoot, destinationChoice);
        Files.createDirectories(destination);

        String worldFolder = sanitize(worldRoot.getFileName().toString());
        String fileName = "WorldTransfer-" + sanitize(targetName) + "-"
            + LocalDateTime.now().format(FILE_TIME) + ".zip";
        Path archive = destination.resolve(fileName);
        Path temporary = destination.resolve(fileName + ".part");
        // Everything lives under one folder inside the ZIP, so unzipping into "saves" produces a real world.
        String prefix = worldFolder + "/";

        try (OutputStream output = Files.newOutputStream(temporary);
             ZipOutputStream zip = new ZipOutputStream(output)) {

            try (var files = Files.walk(worldRoot)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    Path relative = worldRoot.relativize(file);
                    String entryName = relative.toString().replace('\\', '/');
                    if (!shouldInclude(entryName, includePlayerData, includeAdvancements,
                        includeEntities, includeWorldData)) {
                        return;
                    }
                    // These two are rewritten below.
                    if (entryName.equals("level.dat")) {
                        return;
                    }
                    if (includePlayerData && isLivePlayerFile(entryName, snapshots.keySet())) {
                        return;
                    }
                    try {
                        zip.putNextEntry(new ZipEntry(prefix + entryName));
                        Files.copy(file, zip);
                        zip.closeEntry();
                    } catch (IOException exception) {
                        throw new ArchiveWriteException(exception);
                    }
                });
            }

            // 1. level.dat with the new host recorded as the owner of the save.
            zip.putNextEntry(new ZipEntry(prefix + "level.dat"));
            writeCompressedTag(zip, newHostLevelData(worldRoot, targetUuid));
            zip.closeEntry();

            if (includePlayerData) {
                // 2. One up-to-date .dat per online player, under that player's own UUID.
                for (Map.Entry<UUID, Snapshot> entry : snapshots.entrySet()) {
                    CompoundTag tag = strippedPlayerData(entry.getValue().data());
                    zip.putNextEntry(new ZipEntry(prefix + "playerdata/" + entry.getKey() + ".dat"));
                    writeCompressedTag(zip, tag);
                    zip.closeEntry();

                    // 3. The same data keyed by name, used only if a player's UUID differs in the new session.
                    zip.putNextEntry(new ZipEntry(prefix + SNAPSHOT_DIR + "/players/"
                        + sanitize(entry.getValue().name()).toLowerCase(Locale.ROOT) + ".dat"));
                    writeCompressedTag(zip, tag);
                    zip.closeEntry();
                }

                zip.putNextEntry(new ZipEntry(prefix + MARKER_FILE));
                zip.write(markerJson(targetName, targetUuid, sourceName, snapshots)
                    .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (ArchiveWriteException exception) {
            Files.deleteIfExists(temporary);
            throw exception.cause;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }

        try {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        return archive;
    }

    /**
     * The one change that actually moves the host role: Data.singleplayer_uuid.
     */
    private static CompoundTag newHostLevelData(Path worldRoot, UUID targetUuid) throws IOException {
        CompoundTag level = NbtIo.readCompressed(worldRoot.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag data = level.getCompoundOrEmpty("Data");
        data.putIntArray("singleplayer_uuid", UUIDUtil.uuidToIntArray(targetUuid));
        data.remove("Player"); // legacy pre-1.21 host slot; never read today, but it must not linger
        level.put("Data", data);
        return level;
    }

    /**
     * Player files are written without the UUID tag. Entity.load() would otherwise overwrite the joining
     * player's UUID with the one from the old session, which is exactly how identities get swapped.
     */
    private static CompoundTag strippedPlayerData(CompoundTag source) {
        CompoundTag copy = source.copy();
        copy.remove("UUID");
        return copy;
    }

    private static CompoundTag serializePlayer(MinecraftServer server, ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        player.saveWithoutId(output); // same call vanilla PlayerDataStorage.save uses
        return output.buildResult();
    }

    private static boolean isLivePlayerFile(String entryName, Set<UUID> uuids) {
        if (!entryName.startsWith("playerdata/") || !entryName.endsWith(".dat")) {
            return false;
        }
        String stem = entryName.substring("playerdata/".length(), entryName.length() - ".dat".length());
        for (UUID uuid : uuids) {
            if (stem.equalsIgnoreCase(uuid.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldInclude(String path, boolean includePlayerData, boolean includeAdvancements,
                                         boolean includeEntities, boolean includeWorldData) {
        if (path.equals("session.lock") || path.equals("level.dat_old")) {
            return false;
        }
        if (path.endsWith(".dat_old") || path.endsWith(".part")) {
            return false;
        }
        // Artifacts of an earlier transfer must not travel with the world.
        if (path.equals(MARKER_FILE) || path.startsWith(SNAPSHOT_DIR + "/")
            || path.startsWith("world-transfer-")) {
            return false;
        }
        if (!includePlayerData && (path.startsWith("playerdata/") || path.startsWith("data/command_storage"))) {
            return false;
        }
        if (!includeAdvancements && path.startsWith("advancements/")) {
            return false;
        }
        if (!includeEntities && path.startsWith("entities/")) {
            return false;
        }
        if (!includeWorldData && path.startsWith("data/")) {
            return false;
        }
        return true;
    }

    private static String markerJson(String targetName, UUID targetUuid, String sourceName,
                                     Map<UUID, Snapshot> snapshots) {
        StringBuilder participants = new StringBuilder();
        for (Snapshot snapshot : snapshots.values()) {
            if (participants.length() > 0) {
                participants.append(", ");
            }
            participants.append('"').append(escape(snapshot.name())).append('"');
        }
        return "{\n"
            + "  \"format\": 5,\n"
            + "  \"newHost\": \"" + escape(targetName) + "\",\n"
            + "  \"newHostUuid\": \"" + targetUuid + "\",\n"
            + "  \"previousHost\": \"" + escape(sourceName) + "\",\n"
            + "  \"participants\": [" + participants + "]\n"
            + "}\n";
    }

    // ------------------------------------------------------------------
    // Import safety net
    // ------------------------------------------------------------------

    /**
     * Only does something when a player joins with no playerdata file of their own, which happens when their
     * UUID differs between the two sessions (online host + offline clients, or the other way round). In that
     * case their data is restored from the name-keyed snapshot instead of letting them spawn empty.
     */
    private static void tickNameFallback(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path marker = worldRoot.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SEEN_NAMES.add(player.getGameProfile().name().toLowerCase(Locale.ROOT));
            if (!FALLBACK_DONE.add(player.getUUID())) {
                continue;
            }
            try {
                if (Files.isRegularFile(worldRoot.resolve("playerdata").resolve(player.getUUID() + ".dat"))) {
                    continue; // vanilla already gave this player their own data
                }
                Path snapshot = worldRoot.resolve(SNAPSHOT_DIR).resolve("players")
                    .resolve(sanitize(player.getGameProfile().name()).toLowerCase(Locale.ROOT) + ".dat");
                if (!Files.isRegularFile(snapshot)) {
                    continue;
                }
                CompoundTag tag = strippedPlayerData(
                    NbtIo.readCompressed(snapshot, NbtAccounter.unlimitedHeap()));
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
                restorePosition(player, tag);
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
                player.sendSystemMessage(Component.literal(
                    "World Transfer: your inventory and position were restored from the previous world."));
                LOGGER.info("Restored {} from the name snapshot (UUID changed between sessions)",
                    player.getGameProfile().name());
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not restore {} from a name snapshot", player.getGameProfile().name(), exception);
            }
        }

        try {
            if (allParticipantsSeen(Files.readString(marker))) {
                Files.deleteIfExists(marker); // transfer finished; stop arming the fallback
                LOGGER.info("All transferred players have joined; transfer marker removed");
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean allParticipantsSeen(String json) {
        Matcher block = PARTICIPANT_NAMES.matcher(json);
        if (!block.find()) {
            return false;
        }
        Matcher names = QUOTED.matcher(block.group(1));
        boolean any = false;
        while (names.find()) {
            any = true;
            if (!SEEN_NAMES.contains(names.group(1).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return any;
    }

    private static void restorePosition(ServerPlayer player, CompoundTag snapshot) {
        var position = snapshot.getList("Pos").orElse(null);
        if (position != null && position.size() >= 3) {
            var x = position.getDouble(0).orElse(null);
            var y = position.getDouble(1).orElse(null);
            var z = position.getDouble(2).orElse(null);
            if (x != null && y != null && z != null) {
                player.teleportTo(x, y, z);
            }
        }
        var rotation = snapshot.getList("Rotation").orElse(null);
        if (rotation != null && rotation.size() >= 2) {
            var yaw = rotation.getFloat(0).orElse(null);
            var pitch = rotation.getFloat(1).orElse(null);
            if (yaw != null && pitch != null) {
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.setYHeadRot(yaw);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void writeCompressedTag(ZipOutputStream zip, CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        bytes.writeTo(zip);
    }

    private static Path findExportDirectory(Path worldRoot, String choice) {
        if (choice.equalsIgnoreCase("minecraft")) {
            Path minecraftFolder = worldRoot.getParent() == null ? worldRoot : worldRoot.getParent().getParent();
            return minecraftFolder == null ? worldRoot : minecraftFolder;
        }
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
        if (Files.isDirectory(desktop) || !Files.exists(desktop)) {
            return desktop;
        }
        Path minecraftFolder = worldRoot.getParent() == null ? worldRoot : worldRoot.getParent().getParent();
        return minecraftFolder == null ? worldRoot : minecraftFolder;
    }

    private static String sanitize(String value) {
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isEmpty() ? "world" : safe;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class ArchiveWriteException extends RuntimeException {
        private final IOException cause;

        private ArchiveWriteException(IOException cause) {
            this.cause = cause;
        }
    }
}
