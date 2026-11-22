/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package kernitus.plugin.OldCombatMechanics.utilities.storage;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.io.BasicOutputBuffer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Stores data associated with players to disk, persisting across server restarts.
 */
public class PlayerStorage {

    private static final int MAX_TRANSIENT_PLAYERS = 1024;
    private static final Object SCHEDULING = new Object();

    private static Object plugin;
    private static Path dataFilePath;
    private static DocumentCodec documentCodec;
    private static CodecRegistry codecRegistry;
    private static final Map<UUID, Map<UUID, String>> persistentModesets = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, String>> transientModesets = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<UUID> transientOrder = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Object> saveTask = new AtomicReference<>();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static final ReentrantLock FILE_WRITE_LOCK = new ReentrantLock();
    private static volatile Predicate<UUID> onlinePlayerCheck = uuid -> SchedulerBridge.isOnlinePlayer(uuid);

    public static void initialise(OCMMain plugin) {
        initialise((Object) plugin, SchedulerBridge.dataFilePath(plugin));
    }

    static void initialise(@Nullable Object plugin, Path filePath) {
        cancelPendingSave();
        PlayerStorage.plugin = plugin;
        PlayerStorage.dataFilePath = filePath;

        codecRegistry = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new DocumentCodec()),
                CodecRegistries.fromCodecs(new PlayerDataCodec()),
                CodecRegistries.fromProviders(new BsonValueCodecProvider(), new ValueCodecProvider())
        );
        documentCodec = new DocumentCodec(codecRegistry);

        persistentModesets.clear();
        transientModesets.clear();
        transientOrder.clear();
        dirty.set(false);
        persistentModesets.putAll(loadData());
    }

    static void setOnlinePlayerCheck(Predicate<UUID> check) {
        onlinePlayerCheck = check;
    }

    static void resetOnlinePlayerCheck() {
        onlinePlayerCheck = uuid -> SchedulerBridge.isOnlinePlayer(uuid);
    }

    private static void cancelPendingSave() {
        final Object pending = saveTask.getAndSet(null);
        if (pending == null || pending == SCHEDULING) return;
        if (pending instanceof Runnable) {
            ((Runnable) pending).run();
        } else {
            SchedulerBridge.cancel(pending);
        }
    }

    private static Map<UUID, Map<UUID, String>> loadData() {
        final Map<UUID, Map<UUID, String>> loaded = new HashMap<>();
        if (dataFilePath == null || Files.notExists(dataFilePath)) return loaded;

        try {
            final byte[] bytes = Files.readAllBytes(dataFilePath);
            final BsonReader reader = new BsonBinaryReader(ByteBuffer.wrap(bytes));
            final Document root = documentCodec.decode(reader, DecoderContext.builder().build());
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (!(entry.getValue() instanceof Document)) continue;
                final UUID playerId;
                try {
                    playerId = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                final PlayerData playerData = PlayerData.fromDocument((Document) entry.getValue());
                final Map<UUID, String> byWorld = copyModesets(playerData.getModesetByWorld());
                if (!byWorld.isEmpty()) {
                    loaded.put(playerId, byWorld);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (plugin != null) {
                SchedulerBridge.logError(plugin, "Error loading player data", e);
            }
        }
        return loaded;
    }

    public static void scheduleSave() {
        final Object currentPlugin = plugin;
        if (currentPlugin == null) return;
        scheduleSaveWithTask(
                () -> SchedulerBridge.scheduleAsyncSave(currentPlugin, () -> {
                    try {
                        writeSnapshot();
                    } finally {
                        saveTask.set(null);
                    }
                }),
                SchedulerBridge::cancel
        );
    }

    static void scheduleSaveForTest(Supplier<Runnable> taskScheduler) {
        scheduleSaveWithTask(taskScheduler::get, task -> ((Runnable) task).run());
    }

    private static void scheduleSaveWithTask(Supplier<Object> taskScheduler, Consumer<Object> canceller) {
        if (!dirty.get() || !saveTask.compareAndSet(null, SCHEDULING)) return;

        Object scheduled = null;
        try {
            scheduled = taskScheduler.get();
        } finally {
            if (scheduled == null) {
                saveTask.compareAndSet(SCHEDULING, null);
            } else if (!saveTask.compareAndSet(SCHEDULING, scheduled)) {
                canceller.accept(scheduled);
            }
        }
    }

    public static void instantSave() {
        cancelPendingSave();
        writeSnapshot();
    }

    private static void writeSnapshot() {
        if (dataFilePath == null || documentCodec == null) return;

        FILE_WRITE_LOCK.lock();
        try {
            if (dataFilePath == null || documentCodec == null) return;
            dirty.set(false);

            final Document snapshot = encodePersistentSnapshot();
            final BasicOutputBuffer outputBuffer = new BasicOutputBuffer();
            final byte[] bytes;
            try {
                final BsonWriter writer = new BsonBinaryWriter(outputBuffer);
                documentCodec.encode(writer, snapshot, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
                writer.flush();
                bytes = outputBuffer.toByteArray();
            } finally {
                outputBuffer.close();
            }

            try {
                final Path parent = dataFilePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                final Path tempPath = dataFilePath.resolveSibling(dataFilePath.getFileName() + ".tmp");
                Files.write(tempPath, bytes);
                try {
                    Files.move(tempPath, dataFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempPath, dataFilePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                if (plugin != null) {
                    SchedulerBridge.logError(plugin, "Error saving player data", e);
                }
            }
        } finally {
            FILE_WRITE_LOCK.unlock();
        }
    }

    private static Document encodePersistentSnapshot() {
        final Document root = new Document();
        for (Map.Entry<UUID, Map<UUID, String>> playerEntry : persistentModesets.entrySet()) {
            final Map<UUID, String> byWorld = playerEntry.getValue();
            if (byWorld == null || byWorld.isEmpty()) continue;

            final Document modesetByWorldDoc = new Document();
            for (Map.Entry<UUID, String> worldEntry : byWorld.entrySet()) {
                if (worldEntry.getKey() != null && worldEntry.getValue() != null) {
                    modesetByWorldDoc.put(worldEntry.getKey().toString(), worldEntry.getValue());
                }
            }
            if (!modesetByWorldDoc.isEmpty()) {
                final Document playerDoc = new Document();
                playerDoc.put("modesetByWorld", modesetByWorldDoc);
                root.put(playerEntry.getKey().toString(), playerDoc);
            }
        }
        return root;
    }

    public static PlayerData getPlayerData(UUID uuid) {
        Map<UUID, String> byWorld = persistentModesets.get(uuid);
        if (byWorld == null) {
            byWorld = transientModesets.get(uuid);
        }
        final PlayerData playerData = new PlayerData();
        if (byWorld != null && !byWorld.isEmpty()) {
            playerData.setModesetByWorld(new HashMap<>(byWorld));
        }
        return playerData;
    }

    public static void setPlayerData(UUID uuid, PlayerData playerData) {
        final Map<UUID, String> copy = copyModesets(playerData.getModesetByWorld());
        if (isPersistentPlayer(uuid)) {
            transientModesets.remove(uuid);
            final Map<UUID, String> previous = copy.isEmpty()
                    ? persistentModesets.remove(uuid)
                    : persistentModesets.put(uuid, copy);
            if (!copy.equals(previous == null ? Collections.<UUID, String>emptyMap() : previous)) {
                dirty.set(true);
            }
            return;
        }
        if (copy.isEmpty()) {
            transientModesets.remove(uuid);
            return;
        }
        if (transientModesets.put(uuid, copy) == null) {
            transientOrder.offer(uuid);
            evictExcessTransientEntries();
        }
    }

    private static void evictExcessTransientEntries() {
        while (transientModesets.size() > MAX_TRANSIENT_PLAYERS) {
            final UUID oldest = transientOrder.poll();
            if (oldest == null) break;
            transientModesets.remove(oldest);
        }
    }

    private static boolean isPersistentPlayer(UUID uuid) {
        return persistentModesets.containsKey(uuid) || onlinePlayerCheck.test(uuid);
    }

    private static Map<UUID, String> copyModesets(@Nullable Map<UUID, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<UUID, String> copy = new HashMap<>();
        for (Map.Entry<UUID, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? Collections.<UUID, String>emptyMap() : Collections.unmodifiableMap(copy);
    }

    private static final class SchedulerBridge {
        static Path dataFilePath(OCMMain plugin) {
            return Paths.get(plugin.getDataFolder() + File.separator + "players.bson");
        }

        static boolean isOnlinePlayer(UUID uuid) {
            return Bukkit.getServer() == null || Bukkit.getPlayer(uuid) != null;
        }

        static @Nullable Object scheduleAsyncSave(Object pluginObj, Runnable action) {
            final OCMMain ocm = (OCMMain) pluginObj;
            if (!ocm.isEnabled()) return null;
            return Bukkit.getScheduler().runTaskLaterAsynchronously(ocm, action, 2400L);
        }

        static void cancel(Object task) {
            ((BukkitTask) task).cancel();
        }

        static void logError(Object pluginObj, String message, Throwable error) {
            ((OCMMain) pluginObj).getLogger().log(Level.SEVERE, message, error);
        }
    }
}
