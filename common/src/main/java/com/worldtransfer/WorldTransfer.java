package com.worldtransfer;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Server half of World Transfer.
 *
 * <p>Two delivery routes share one export pipeline:</p>
 * <ul>
 *   <li><b>ZIP</b> - write every file of the world into an archive the host hands over manually.</li>
 *   <li><b>Direct</b> - send the receiver an index of the world, let them say which files they are
 *       missing, and stream only those over the LAN connection that already exists.</li>
 * </ul>
 *
 * <p>Both produce a save whose {@code level.dat -> Data.singleplayer_uuid} points at the new host,
 * which is the field that actually moves ownership. See the README.</p>
 */
public final class WorldTransfer {
    public static final String MOD_ID = WorldTransferConstants.MOD_ID;
    public static final String MOD_VERSION = WorldTransferConstants.MOD_VERSION;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> FALLBACK_DONE = new HashSet<>();
    private static final Set<String> SEEN_NAMES = new HashSet<>();

    /** Everything needed to produce a transferred copy of the world, computed once up front. */
    private static final class Plan {
        UUID transferId;
        String worldId;
        Path worldRoot;
        String worldFolder;
        String gameVersion;
        int worldVersion;
        /** Files whose content differs from disk: level.dat, player data, metadata. */
        final Map<String, byte[]> overrides = new LinkedHashMap<>();
        final TransferData.Index index = new TransferData.Index();
    }

    /** A direct transfer in flight, ticked forward on the server thread. */
    private static final class Session {
        UUID transferId;
        UUID targetUuid;
        String targetName;
        Plan plan;
        String state = "index";
        int outSeq;
        byte[] indexBytes;
        int indexOffset;
        final ByteArrayOutputStream needBuffer = new ByteArrayOutputStream();
        Path bundle;
        InputStream bundleStream;
        long bundleSize;
        long bundleSent;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void initialize() {
        TransferPlatform.current().registerReplyHandler(WorldTransfer::handleReply);
        TransferPlatform.current().registerServerTick(server -> {
            tickSessions(server);
            tickNameFallback(server);
        });
        TransferPlatform.current().registerCommands(WorldTransfer::registerCommands);
    }

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var sendPlayer = Commands.argument("player", EntityArgument.player())
            .executes(context -> startDirect(context, Options.defaults()));
        sendPlayer.then(Commands.argument("options", StringArgumentType.greedyString())
            .executes(context -> startDirect(context,
                Options.parse(StringArgumentType.getString(context, "options")))));
        var preparePlayer = Commands.argument("player", EntityArgument.player())
            .executes(context -> exportZip(context, Options.defaults()));
        preparePlayer.then(Commands.argument("options", StringArgumentType.greedyString())
            .executes(context -> exportZip(context,
                Options.parse(StringArgumentType.getString(context, "options")))));
        dispatcher.register(Commands.literal("worldtransfer")
            .then(Commands.literal("send").then(sendPlayer))
            .then(Commands.literal("prepare").then(preparePlayer)));
    }

    /**
     * What to include in a transfer. Parsed from a free-form token list so the command never depends
     * on argument order or count: unknown tokens are ignored, missing ones keep their default.
     *
     * <p>Accepted tokens: {@code playerdata}, {@code advancements}, {@code entities},
     * {@code worlddata}, {@code cheats}, each with a {@code no} prefix to turn it off, plus
     * {@code desktop} or {@code minecraft} to choose where a ZIP is written.</p>
     */
    public record Options(boolean playerData, boolean advancements, boolean entities, boolean worldData,
                          boolean carryCheats, boolean toDesktop) {
        public static Options defaults() {
            // Cheats default to off: see the permissions note in the README.
            return new Options(true, true, true, true, false, true);
        }

        public static Options parse(String text) {
            boolean playerData = true;
            boolean advancements = true;
            boolean entities = true;
            boolean worldData = true;
            boolean cheats = false;
            boolean desktop = true;
            for (String raw : text.toLowerCase(Locale.ROOT).split("[\\s,;]+")) {
                boolean on = !raw.startsWith("no");
                String key = on ? raw : raw.substring(2);
                switch (key) {
                    case "playerdata" -> playerData = on;
                    case "advancements" -> advancements = on;
                    case "entities" -> entities = on;
                    case "worlddata" -> worldData = on;
                    case "cheats", "commands" -> cheats = on;
                    case "desktop" -> desktop = true;
                    case "minecraft", "gamedir" -> desktop = false;
                    default -> {
                    }
                }
            }
            return new Options(playerData, advancements, entities, worldData, cheats, desktop);
        }
    }

    private static int startDirect(CommandContext<CommandSourceStack> context, Options options)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return startDirect(context.getSource().getServer(), EntityArgument.getPlayer(context, "player"),
            context.getSource().getPlayer(), options);
    }

    private static int exportZip(CommandContext<CommandSourceStack> context, Options options)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return exportZip(context.getSource().getServer(), EntityArgument.getPlayer(context, "player"),
            context.getSource().getPlayer(), options);
    }

    // ------------------------------------------------------------------
    // Export pipeline, shared by both routes
    // ------------------------------------------------------------------

    private static Plan buildPlan(MinecraftServer server, ServerPlayer target, ServerPlayer source,
                                  Options options) throws IOException {
        server.getPlayerList().saveAll();
        server.saveEverything(true, true, true);

        boolean includePlayerData = options.playerData();
        boolean includeAdvancements = options.advancements();
        boolean includeEntities = options.entities();
        boolean includeWorldData = options.worldData();

        Plan plan = new Plan();
        plan.transferId = UUID.randomUUID();
        // LevelResource.ROOT's id is literally "." - getWorldPath(ROOT) is <saveDir>/. unless
        // normalized, and getFileName() on that returns "." instead of the real folder name. That
        // "." was showing up as the world name and, once sanitized, resolving straight to the saves
        // folder itself - which is why it always looked "taken" and got a "-2" suffix.
        plan.worldRoot = server.getWorldPath(LevelResource.ROOT).normalize();
        plan.worldFolder = plan.worldRoot.getFileName().toString();
        plan.worldId = readOrCreateWorldId(plan.worldRoot);

        // 1. level.dat, rewritten so the chosen player owns the save.
        CompoundTag level = NbtIo.readCompressed(plan.worldRoot.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag data = level.getCompoundOrEmpty("Data");
        plan.gameVersion = data.getCompoundOrEmpty("Version").getStringOr("Name", "unknown");
        plan.worldVersion = SharedConstants.WORLD_VERSION;
        data.putIntArray("singleplayer_uuid", UUIDUtil.uuidToIntArray(target.getUUID()));
        data.remove("Player"); // legacy host slot; unread today, but it must not linger
        if (!options.carryCheats()) {
            // Data.allowCommands is what lets guests of the new host's LAN game run commands
            // (IntegratedServer.getCustomPermissionLevel). Off unless explicitly carried over; the
            // new host can turn it back on from the world's own options.
            data.putBoolean("allowCommands", false);
        }
        level.put("Data", data);
        plan.overrides.put("level.dat", toCompressedBytes(level));

        // 2. Live player data for everyone online, under their own UUID and under their name.
        List<String> participants = new ArrayList<>();
        if (includePlayerData) {
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                String name = online.getGameProfile().name();
                byte[] bytes = toCompressedBytes(stripUuid(serializePlayer(server, online)));
                plan.overrides.put("playerdata/" + online.getUUID() + ".dat", bytes);
                plan.overrides.put(TransferData.PLAYERS_DIR + "/" + TransferData.safeName(name) + ".dat", bytes);
                participants.add(name);
            }
        }

        // 3. Marker, ownership history, stable world id.
        String sourceName = source == null ? "server" : source.getGameProfile().name();
        plan.overrides.put(TransferData.MARKER_FILE, TransferData.markerJson(
            plan.transferId.toString(), plan.worldId, target.getGameProfile().name(),
            target.getUUID().toString(), sourceName, plan.gameVersion, plan.worldVersion,
            MOD_VERSION, participants).getBytes(StandardCharsets.UTF_8));
        String history = appendHistory(plan.worldRoot, sourceName, target.getGameProfile().name(),
            plan.gameVersion, plan.transferId.toString());
        plan.overrides.put(TransferData.HISTORY_FILE, history.getBytes(StandardCharsets.UTF_8));
        // Also record it in the world being transferred from, so the sending host's own
        // "Ownership history" screen has something to show.
        Files.writeString(plan.worldRoot.resolve(TransferData.HISTORY_FILE), history);
        plan.overrides.put(TransferData.WORLD_ID_FILE, plan.worldId.getBytes(StandardCharsets.UTF_8));

        // 4. Index: everything on disk that survives the filters, plus the overrides.
        Map<String, TransferData.FileEntry> entries = new LinkedHashMap<>();
        try (var files = Files.walk(plan.worldRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = plan.worldRoot.relativize(file).toString().replace('\\', '/');
                if (!shouldInclude(path, options) || plan.overrides.containsKey(path)) {
                    continue;
                }
                entries.put(path, new TransferData.FileEntry(path, Files.size(file), TransferData.sha1(file)));
            }
        }
        for (Map.Entry<String, byte[]> override : plan.overrides.entrySet()) {
            entries.put(override.getKey(), new TransferData.FileEntry(
                override.getKey(), override.getValue().length, TransferData.sha1(override.getValue())));
        }
        plan.index.entries().addAll(entries.values());
        plan.index.meta().put("worldId", plan.worldId);
        plan.index.meta().put("world", plan.worldFolder);
        plan.index.meta().put("transferId", plan.transferId.toString());
        plan.index.meta().put("game", plan.gameVersion);
        plan.index.meta().put("worldVersion", String.valueOf(plan.worldVersion));
        plan.index.meta().put("mod", MOD_VERSION);
        plan.index.meta().put("host", sourceName);
        plan.index.meta().put("target", target.getGameProfile().name());
        plan.index.meta().put("targetUuid", target.getUUID().toString());
        return plan;
    }

    /** Writes the requested paths into a ZIP, taking each file from the override map or from disk. */
    private static void writeBundle(OutputStream output, Plan plan, List<String> paths, String prefix)
        throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String path : paths) {
                byte[] override = plan.overrides.get(path);
                Path file = plan.worldRoot.resolve(path);
                if (override == null && !Files.isRegularFile(file)) {
                    continue; // deleted between indexing and sending
                }
                zip.putNextEntry(new ZipEntry(prefix + path));
                if (override != null) {
                    zip.write(override);
                } else {
                    Files.copy(file, zip);
                }
                zip.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------
    // Route 1: ZIP on disk
    // ------------------------------------------------------------------

    private static int exportZip(MinecraftServer server, ServerPlayer target, ServerPlayer source,
                                 Options options) {
        try {
            Plan plan = buildPlan(server, target, source, options);
            Path destination = findExportDirectory(plan.worldRoot, options.toDesktop() ? "desktop" : "minecraft");
            Files.createDirectories(destination);
            String fileName = "WorldTransfer-" + TransferData.sanitize(target.getGameProfile().name())
                + "-" + LocalDateTime.now().format(FILE_TIME) + ".zip";
            Path archive = destination.resolve(fileName);
            Path temporary = destination.resolve(fileName + ".part");

            List<String> all = new ArrayList<>();
            for (TransferData.FileEntry entry : plan.index.entries()) {
                all.add(entry.path());
            }
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writeBundle(output, plan, all, TransferData.sanitize(plan.worldFolder) + "/");
            }
            moveInto(temporary, archive);

            if (source != null) {
                source.sendSystemMessage(Component.literal("Transfer ZIP created: "
                    + archive.toAbsolutePath() + " (" + TransferData.humanBytes(Files.size(archive))
                    + "). Send it to " + target.getGameProfile().name() + "."));
            }
            target.sendSystemMessage(Component.literal(
                "A transfer ZIP naming you as the new host was created. Unzip it into your saves folder."));
            return 1;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("ZIP export failed", exception);
            if (source != null) {
                source.sendSystemMessage(Component.literal("World transfer failed: " + exception.getMessage()));
            }
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Route 2: direct, over the LAN connection
    // ------------------------------------------------------------------

    private static int startDirect(MinecraftServer server, ServerPlayer target, ServerPlayer source,
                                   Options options) {
        if (!TransferPlatform.current().canSend(target)) {
            if (source != null) {
                source.sendSystemMessage(Component.literal(target.getGameProfile().name()
                    + " does not have World Transfer installed, so a direct send is not possible."
                    + " Use the ZIP option instead."));
            }
            return 0;
        }
        if (!SESSIONS.isEmpty()) {
            if (source != null) {
                source.sendSystemMessage(Component.literal(
                    "Another world transfer is already running. Wait for it to finish before starting a new one."));
            }
            return 0;
        }

        try {
            Plan plan = buildPlan(server, target, source, options);
            Session session = new Session();
            session.transferId = plan.transferId;
            session.targetUuid = target.getUUID();
            session.targetName = target.getGameProfile().name();
            session.plan = plan;
            session.indexBytes = plan.index.encode();
            SESSIONS.put(session.transferId, session);

            if (source != null) {
                source.sendSystemMessage(Component.literal("Offering the world to " + session.targetName
                    + " (" + TransferData.humanBytes(plan.index.totalBytes())
                    + " in total). Waiting for them to accept."));
            }
            LOGGER.info("Direct transfer {} offered to {}", session.transferId, session.targetName);
            return 1;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Direct transfer could not start", exception);
            if (source != null) {
                source.sendSystemMessage(Component.literal("World transfer failed: " + exception.getMessage()));
            }
            return 0;
        }
    }

    private static void handleReply(MinecraftServer server, ServerPlayer player, TransferNet.Reply reply) {
        Session session = SESSIONS.get(reply.transferId());
        if (session == null || !session.targetUuid.equals(player.getUUID())) {
            return;
        }
        switch (reply.action()) {
            case TransferNet.ACCEPT -> {
                session.state = "collect";
                LOGGER.info("Transfer {} accepted by {}", session.transferId, session.targetName);
            }
            case TransferNet.NEED -> {
                session.needBuffer.writeBytes(reply.chunk());
                if (reply.last()) {
                    prepareBundle(server, session);
                }
            }
            case TransferNet.DECLINE -> {
                notifyHost(server, session, session.targetName + " declined the world transfer.");
                finish(session, "declined");
            }
            case TransferNet.DONE -> {
                notifyHost(server, session, session.targetName
                    + " received the world and can open it from their singleplayer list.");
                finish(session, "completed");
            }
            case TransferNet.FAILED -> {
                notifyHost(server, session, "Transfer to " + session.targetName + " failed: " + reply.detail());
                finish(session, "failed");
            }
            default -> {
            }
        }
    }

    private static void prepareBundle(MinecraftServer server, Session session) {
        try {
            String wanted = new String(TransferData.gunzip(session.needBuffer.toByteArray()),
                StandardCharsets.UTF_8);
            List<String> paths = new ArrayList<>();
            for (String line : wanted.split("\n")) {
                if (!line.isEmpty()) {
                    paths.add(line);
                }
            }
            session.bundle = Files.createTempFile("worldtransfer-", ".zip");
            try (OutputStream output = Files.newOutputStream(session.bundle)) {
                writeBundle(output, session.plan, paths, "");
            }
            session.bundleSize = Files.size(session.bundle);
            session.bundleStream = Files.newInputStream(session.bundle);
            session.outSeq = 0;
            session.state = "sending";
            notifyHost(server, session, "Sending " + TransferData.humanBytes(session.bundleSize)
                + " to " + session.targetName + " (" + paths.size() + " files).");
            LOGGER.info("Transfer {}: bundle of {} files, {} bytes",
                session.transferId, paths.size(), session.bundleSize);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not build transfer bundle", exception);
            notifyHost(server, session, "Could not build the transfer bundle: " + exception.getMessage());
            finish(session, "bundle failed");
        }
    }

    private static void tickSessions(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            ServerPlayer target = server.getPlayerList().getPlayer(session.targetUuid);
            if (target == null) {
                finish(session, "target left");
                continue;
            }
            try {
                if (session.state.equals("index")) {
                    if (sendIndexChunk(target, session)) {
                        session.state = "waiting";
                    }
                } else if (session.state.equals("sending")) {
                    // Four 256 KiB packets per tick caps the stream at roughly 20 MB/s.
                    for (int i = 0; i < 4 && session.state.equals("sending"); i++) {
                        if (sendBundleChunk(target, session)) {
                            session.state = "sent";
                        }
                    }
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Transfer {} aborted", session.transferId, exception);
                finish(session, "error");
            }
        }
    }

    private static boolean sendIndexChunk(ServerPlayer target, Session session) {
        int remaining = session.indexBytes.length - session.indexOffset;
        int size = Math.min(TransferNet.S2C_CHUNK, remaining);
        byte[] chunk = Arrays.copyOfRange(session.indexBytes, session.indexOffset, session.indexOffset + size);
        session.indexOffset += size;
        boolean last = session.indexOffset >= session.indexBytes.length;
        TransferPlatform.current().send(target, new TransferNet.Blob(
            session.transferId, TransferNet.STREAM_INDEX, session.outSeq++, last, chunk));
        return last;
    }

    private static boolean sendBundleChunk(ServerPlayer target, Session session) throws IOException {
        byte[] buffer = new byte[TransferNet.S2C_CHUNK];
        int read = session.bundleStream.read(buffer);
        if (read <= 0) {
            TransferPlatform.current().send(target, new TransferNet.Blob(
                session.transferId, TransferNet.STREAM_BUNDLE, session.outSeq++, true, new byte[0]));
            return true;
        }
        session.bundleSent += read;
        boolean last = session.bundleSent >= session.bundleSize;
        TransferPlatform.current().send(target, new TransferNet.Blob(session.transferId, TransferNet.STREAM_BUNDLE,
            session.outSeq++, last, read == buffer.length ? buffer : Arrays.copyOf(buffer, read)));
        return last;
    }

    private static void notifyHost(MinecraftServer server, Session session, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.isSingleplayerOwner(new NameAndId(player.getUUID(), player.getGameProfile().name()))) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
        LOGGER.info("Transfer {}: {}", session.transferId, message);
    }

    private static void finish(Session session, String reason) {
        SESSIONS.remove(session.transferId);
        try {
            if (session.bundleStream != null) {
                session.bundleStream.close();
            }
            if (session.bundle != null) {
                Files.deleteIfExists(session.bundle);
            }
        } catch (IOException ignored) {
        }
        LOGGER.info("Transfer {} ended: {}", session.transferId, reason);
    }

    // ------------------------------------------------------------------
    // Import safety net: the player's UUID changed between sessions
    // ------------------------------------------------------------------

    private static void tickNameFallback(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path marker = worldRoot.resolve(TransferData.MARKER_FILE);
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
                Path snapshot = worldRoot.resolve(TransferData.PLAYERS_DIR)
                    .resolve(TransferData.safeName(player.getGameProfile().name()) + ".dat");
                if (!Files.isRegularFile(snapshot)) {
                    continue;
                }
                CompoundTag tag = stripUuid(NbtIo.readCompressed(snapshot, NbtAccounter.unlimitedHeap()));
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
                restorePosition(player, tag);
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
                player.sendSystemMessage(Component.literal(
                    "World Transfer: your data was restored from the previous world."));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Name-based restore failed for {}", player.getGameProfile().name(), exception);
            }
        }
        try {
            List<String> participants = TransferData.listField(Files.readString(marker), "participants");
            if (!participants.isEmpty()) {
                boolean allSeen = true;
                for (String name : participants) {
                    if (!SEEN_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                        allSeen = false;
                        break;
                    }
                }
                if (allSeen) {
                    Files.deleteIfExists(marker);
                }
            }
        } catch (IOException ignored) {
        }
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

    private static CompoundTag serializePlayer(MinecraftServer server, ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        player.saveWithoutId(output); // the same call vanilla PlayerDataStorage.save uses
        return output.buildResult();
    }

    /**
     * Player files travel without their UUID tag: {@code Entity.load} would otherwise stamp the old
     * session's UUID onto whoever loads the file, which is exactly how identities get swapped.
     */
    private static CompoundTag stripUuid(CompoundTag source) {
        CompoundTag copy = source.copy();
        copy.remove("UUID");
        return copy;
    }

    private static byte[] toCompressedBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    private static String readOrCreateWorldId(Path worldRoot) throws IOException {
        Path file = worldRoot.resolve(TransferData.WORLD_ID_FILE);
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        String id = UUID.randomUUID().toString();
        Files.createDirectories(file.getParent());
        Files.writeString(file, id);
        return id;
    }

    private static String appendHistory(Path worldRoot, String from, String to, String gameVersion,
                                        String transferId) throws IOException {
        Path file = worldRoot.resolve(TransferData.HISTORY_FILE);
        List<TransferData.HistoryEntry> entries = Files.isRegularFile(file)
            ? new ArrayList<>(TransferData.parseHistory(Files.readString(file)))
            : new ArrayList<>();
        entries.add(new TransferData.HistoryEntry(Instant.now().toString(), from, to, gameVersion, transferId));
        return TransferData.writeHistory(entries);
    }

    private static boolean shouldInclude(String path, Options options) {
        boolean includePlayerData = options.playerData();
        boolean includeAdvancements = options.advancements();
        boolean includeEntities = options.entities();
        boolean includeWorldData = options.worldData();
        if (path.equals("session.lock") || path.equals("level.dat_old")) {
            return false;
        }
        if (path.endsWith(".dat_old") || path.endsWith(".part")) {
            return false;
        }
        if (path.startsWith("world-transfer-")) {
            return false; // artifacts of older mod versions
        }
        if (!options.carryCheats() && (path.equals("ops.json") || path.equals("whitelist.json")
            || path.equals("banned-players.json") || path.equals("banned-ips.json"))) {
            return false; // only present in server-style world folders, but never carried silently
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

    private static void moveInto(Path temporary, Path archive) throws IOException {
        try {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        }
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
}
