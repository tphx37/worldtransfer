package com.worldtransfer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Receiving half of a direct transfer. One instance exists at a time.
 *
 * <p>Order of events: the index arrives, the player accepts, the local copy of the world (if any)
 * is hashed and compared, the missing paths are requested, the bundle streams in, and the final
 * world folder is assembled from the bundle plus whatever the local copy already had.</p>
 *
 * <p>Networking callbacks run on the client thread and must stay cheap, so hashing and file
 * assembly happen on a worker thread and outgoing packets are queued for the next client tick.</p>
 */
public final class IncomingTransfer {
    public enum State {
        OFFERED, CHECKING, REQUESTING, RECEIVING, APPLYING, DONE, REFUSED, FAILED
    }

    private static IncomingTransfer current;

    private final UUID transferId;
    private final TransferData.Index index;
    private volatile State state = State.OFFERED;
    private volatile String status = "";
    private volatile String resultFolder = "";
    private volatile long receivedBytes;
    private volatile long expectedBytes;

    private final Deque<TransferNet.Reply> outbox = new ArrayDeque<>();
    private Path bundleFile;
    private OutputStream bundleOut;
    private Path baseWorld;
    private List<String> neededPaths = List.of();

    private IncomingTransfer(UUID transferId, TransferData.Index index) {
        this.transferId = transferId;
        this.index = index;
    }

    public static IncomingTransfer current() {
        return current;
    }

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    public String resultFolder() {
        return resultFolder;
    }

    public TransferData.Index index() {
        return index;
    }

    public long receivedBytes() {
        return receivedBytes;
    }

    public long expectedBytes() {
        return expectedBytes;
    }

    // ------------------------------------------------------------------
    // Incoming packets
    // ------------------------------------------------------------------

    private static final ByteArrayOutputStream INDEX_BUFFER = new ByteArrayOutputStream();
    private static UUID indexTransferId;

    public static void onBlob(TransferNet.Blob blob) {
        if (blob.stream() == TransferNet.STREAM_INDEX) {
            if (!blob.transferId().equals(indexTransferId)) {
                INDEX_BUFFER.reset();
                indexTransferId = blob.transferId();
            }
            INDEX_BUFFER.writeBytes(blob.chunk());
            if (blob.last()) {
                try {
                    TransferData.Index index = TransferData.Index.decode(INDEX_BUFFER.toByteArray());
                    current = new IncomingTransfer(blob.transferId(), index);
                    current.status = index.get("host", "someone") + " offers you the world \""
                        + index.get("world", "?") + "\"";
                } catch (IOException exception) {
                    current = null;
                }
                INDEX_BUFFER.reset();
                indexTransferId = null;
            }
            return;
        }

        IncomingTransfer transfer = current;
        if (transfer == null || !transfer.transferId.equals(blob.transferId())) {
            return;
        }
        transfer.onBundleChunk(blob);
    }

    private void onBundleChunk(TransferNet.Blob blob) {
        try {
            if (bundleOut == null) {
                bundleFile = Files.createTempFile("worldtransfer-in-", ".zip");
                bundleOut = Files.newOutputStream(bundleFile);
                state = State.RECEIVING;
            }
            bundleOut.write(blob.chunk());
            receivedBytes += blob.chunk().length;
            status = "Receiving " + TransferData.humanBytes(receivedBytes)
                + " / " + TransferData.humanBytes(expectedBytes);
            if (blob.last()) {
                bundleOut.close();
                bundleOut = null;
                state = State.APPLYING;
                runAsync(this::applyWorld);
            }
        } catch (IOException exception) {
            fail("Could not write the incoming bundle: " + exception.getMessage());
        }
    }

    /** Called once per client tick; pushes at most a few queued packets so nothing floods. */
    public static void tick() {
        IncomingTransfer transfer = current;
        if (transfer == null) {
            return;
        }
        synchronized (transfer.outbox) {
            for (int i = 0; i < 8 && !transfer.outbox.isEmpty(); i++) {
                ClientPlayNetworking.send(transfer.outbox.poll());
            }
        }
    }

    private void queue(TransferNet.Reply reply) {
        synchronized (outbox) {
            outbox.add(reply);
        }
    }

    // ------------------------------------------------------------------
    // Player decisions
    // ------------------------------------------------------------------

    /** Null when the receiving client can open this save, a reason string when it cannot. */
    public String versionProblem() {
        int worldVersion = index.getInt("worldVersion", SharedConstants.WORLD_VERSION);
        if (worldVersion > SharedConstants.WORLD_VERSION) {
            return "That world was saved by a newer Minecraft (data version " + worldVersion
                + ", yours is " + SharedConstants.WORLD_VERSION + "). Update your game first.";
        }
        return null;
    }

    public String versionWarning() {
        int worldVersion = index.getInt("worldVersion", SharedConstants.WORLD_VERSION);
        if (worldVersion < SharedConstants.WORLD_VERSION) {
            return "The world is older than your game and will be upgraded when you open it.";
        }
        return null;
    }

    public void decline() {
        state = State.REFUSED;
        status = "Declined.";
        queue(TransferNet.Reply.simple(transferId, TransferNet.DECLINE, ""));
        current = null;
    }

    public void accept() {
        if (versionProblem() != null) {
            return;
        }
        state = State.CHECKING;
        status = "Checking which files you already have...";
        queue(TransferNet.Reply.simple(transferId, TransferNet.ACCEPT, ""));
        runAsync(this::computeNeeded);
    }

    // ------------------------------------------------------------------
    // Worker steps
    // ------------------------------------------------------------------

    private void computeNeeded() {
        try {
            baseWorld = findBaseWorld(index.get("worldId", ""));
            Map<String, TransferData.FileEntry> local = baseWorld == null
                ? Map.of()
                : TransferData.indexFolder(baseWorld);
            neededPaths = TransferData.diff(index, local);

            long bytes = 0L;
            Map<String, TransferData.FileEntry> wanted = index.byPath();
            for (String path : neededPaths) {
                TransferData.FileEntry entry = wanted.get(path);
                if (entry != null) {
                    bytes += entry.size();
                }
            }
            expectedBytes = bytes;

            StringBuilder list = new StringBuilder();
            for (String path : neededPaths) {
                list.append(path).append('\n');
            }
            byte[] compressed = TransferData.gzip(list.toString().getBytes(StandardCharsets.UTF_8));

            int chunks = Math.max(1, (compressed.length + TransferNet.C2S_CHUNK - 1) / TransferNet.C2S_CHUNK);
            for (int i = 0; i < chunks; i++) {
                int from = i * TransferNet.C2S_CHUNK;
                int to = Math.min(compressed.length, from + TransferNet.C2S_CHUNK);
                queue(new TransferNet.Reply(transferId, TransferNet.NEED, i, i == chunks - 1,
                    java.util.Arrays.copyOfRange(compressed, from, to)));
            }

            state = State.REQUESTING;
            status = baseWorld == null
                ? "Requesting the full world (" + TransferData.humanBytes(expectedBytes) + ")"
                : "Reusing your existing copy; requesting " + neededPaths.size() + " changed files ("
                    + TransferData.humanBytes(expectedBytes) + ")";
        } catch (IOException | RuntimeException exception) {
            fail("Could not compare with your local copy: " + exception.getMessage());
        }
    }

    private void applyWorld() {
        Path destination = null;
        try {
            Path saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
            Files.createDirectories(saves);
            if (baseWorld != null) {
                // Update the copy we already have, in place. Its folder name already matches this
                // world (that's how findBaseWorld matched it), so handing uniqueFolder() that same
                // name would only ever find it taken and tack on "-2" - every single time, not just
                // the first collision.
                destination = baseWorld;
            } else {
                destination = uniqueFolder(saves, TransferData.sanitize(index.get("world", "TransferredWorld")));
            }

            // Extract everything that arrived, then fill the rest in from the local copy.
            status = "Unpacking...";
            try (InputStream input = Files.newInputStream(bundleFile);
                 ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    Path out = safeResolve(destination, entry.getName());
                    if (out == null) {
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (baseWorld != null && !baseWorld.equals(destination)) {
                // Currently dead in practice since destination now equals baseWorld whenever it's
                // set (see above), but kept as a guard in case that ever changes again.
                status = "Reusing unchanged files...";
                for (TransferData.FileEntry entry : index.entries()) {
                    Path out = safeResolve(destination, entry.path());
                    if (out == null || Files.exists(out)) {
                        continue;
                    }
                    Path from = baseWorld.resolve(entry.path());
                    if (Files.isRegularFile(from)) {
                        Files.createDirectories(out.getParent());
                        Files.copy(from, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            List<String> missing = new ArrayList<>();
            for (TransferData.FileEntry entry : index.entries()) {
                Path out = safeResolve(destination, entry.path());
                if (out == null || !Files.isRegularFile(out)) {
                    missing.add(entry.path());
                }
            }
            if (!missing.isEmpty()) {
                throw new IOException(missing.size() + " files did not arrive (for example "
                    + missing.get(0) + ")");
            }

            resultFolder = destination.getFileName().toString();
            state = State.DONE;
            status = "Done. Open \"" + resultFolder + "\" from your singleplayer world list.";
            queue(TransferNet.Reply.simple(transferId, TransferNet.DONE, resultFolder));
        } catch (IOException | RuntimeException exception) {
            if (destination != null) {
                deleteQuietly(destination);
            }
            fail("Could not assemble the world: " + exception.getMessage());
        } finally {
            try {
                if (bundleFile != null) {
                    Files.deleteIfExists(bundleFile);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void fail(String message) {
        state = State.FAILED;
        status = message;
        queue(TransferNet.Reply.simple(transferId, TransferNet.FAILED, message));
    }

    // ------------------------------------------------------------------
    // Local world lookup and file helpers
    // ------------------------------------------------------------------

    /** Finds an earlier copy of the same world, so only the changed files have to be sent. */
    private static Path findBaseWorld(String worldId) throws IOException {
        if (worldId.isEmpty()) {
            return null;
        }
        Path saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
        if (!Files.isDirectory(saves)) {
            return null;
        }
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        try (var folders = Files.list(saves)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                Path idFile = folder.resolve(TransferData.WORLD_ID_FILE);
                if (!Files.isRegularFile(idFile)) {
                    continue;
                }
                if (!Files.readString(idFile).trim().equals(worldId)) {
                    continue;
                }
                long time = Files.getLastModifiedTime(folder).toMillis();
                if (time > newestTime) {
                    newestTime = time;
                    newest = folder;
                }
            }
        }
        return newest;
    }

    private static Path uniqueFolder(Path saves, String name) throws IOException {
        Path candidate = saves.resolve(name);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = saves.resolve(name + "-" + suffix++);
        }
        Files.createDirectories(candidate);
        return candidate;
    }

    /** Guards against zip entries trying to escape the destination folder. */
    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static void deleteQuietly(Path folder) {
        try (var paths = Files.walk(folder)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void runAsync(Runnable task) {
        Thread thread = new Thread(task, "world-transfer-worker");
        thread.setDaemon(true);
        thread.start();
    }
}
