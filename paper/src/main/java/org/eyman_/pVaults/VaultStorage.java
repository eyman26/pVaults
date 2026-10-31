package org.eyman_.pVaults;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Page cache plus coalesced, single-flight-per-owner disk writes.
 * <p>
 * The cache is the source of truth while the server runs. Every owner has at most one write in
 * flight; snapshots stored while it runs are merged and picked up by the same drain loop, so two
 * pages of the same file can never be written concurrently and clobber each other.
 */
final class VaultStorage {

    static final int PAGE_SIZE = 45;

    private final Path folder;
    private final Logger logger;
    private final ExecutorService io;

    private final Map<UUID, Map<Integer, ItemStack[]>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, ItemStack[]>> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> writing = ConcurrentHashMap.newKeySet();

    VaultStorage(Path folder, Logger logger) {
        this.folder = folder;
        this.logger = logger;
        this.io = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("pVaults-io-", 0).daemon().factory());
    }

    CompletableFuture<ItemStack[]> load(UUID owner, int page) {
        ItemStack[] cached = cachedPage(owner, page);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        return CompletableFuture.supplyAsync(() -> readPage(owner, page), io).thenApply(loaded -> {
            // Another viewer may have stored a newer snapshot while we were reading; theirs wins.
            ItemStack[] existing = cache.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).putIfAbsent(page, loaded);
            return existing != null ? existing : loaded;
        });
    }

    /** {@code snapshot} must be a private copy; it is shared with the cache and the IO thread from here on. */
    void store(UUID owner, int page, ItemStack[] snapshot) {
        Map<Integer, ItemStack[]> pages = cache.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        ItemStack[] previous = pages.put(page, snapshot);
        if (Arrays.equals(previous, snapshot)) return;

        dirty.compute(owner, (k, batch) -> {
            if (batch == null) batch = new HashMap<>();
            batch.put(page, snapshot);
            return batch;
        });
        scheduleWrite(owner);
    }

    void evictIfIdle(UUID owner) {
        if (!dirty.containsKey(owner) && !writing.contains(owner)) {
            cache.remove(owner);
        }
    }

    void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Vault writes did not finish within 10s, flushing the rest on the main thread.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (UUID owner : Set.copyOf(dirty.keySet())) {
            Map<Integer, ItemStack[]> batch = dirty.remove(owner);
            if (batch != null) writePages(owner, batch);
        }
    }

    private ItemStack[] cachedPage(UUID owner, int page) {
        Map<Integer, ItemStack[]> pages = cache.get(owner);
        return pages == null ? null : pages.get(page);
    }

    private void scheduleWrite(UUID owner) {
        if (!writing.add(owner)) return;
        try {
            io.execute(() -> drain(owner));
        } catch (RejectedExecutionException e) {
            // Shutting down: shutdown() flushes whatever is still dirty.
            writing.remove(owner);
        }
    }

    private void drain(UUID owner) {
        boolean failed = false;
        try {
            Map<Integer, ItemStack[]> batch;
            while (!failed && (batch = dirty.remove(owner)) != null) {
                failed = !writePages(owner, batch);
            }
        } finally {
            writing.remove(owner);
        }
        // After a failure the next store() retries; looping here would just spin on a broken disk.
        if (!failed && dirty.containsKey(owner)) scheduleWrite(owner);
    }

    private Path file(UUID owner) {
        return folder.resolve(owner + ".yml");
    }

    private ItemStack[] readPage(UUID owner, int page) {
        ItemStack[] items = new ItemStack[PAGE_SIZE];
        YamlConfiguration yaml = readFile(owner);
        ConfigurationSection section = yaml.getConfigurationSection("pages." + page);
        if (section == null) return items;

        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                if (slot >= 0 && slot < PAGE_SIZE) items[slot] = section.getItemStack(key);
            } catch (NumberFormatException ignored) {
            }
        }
        return items;
    }

    private YamlConfiguration readFile(UUID owner) {
        YamlConfiguration yaml = new YamlConfiguration();
        Path file = file(owner);
        if (Files.notExists(file)) return yaml;
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            // Refuse to continue with an empty vault; a later save would wipe the real data.
            throw new IllegalStateException("Could not read vault file " + file, e);
        }
        return yaml;
    }

    private boolean writePages(UUID owner, Map<Integer, ItemStack[]> pages) {
        try {
            YamlConfiguration yaml = readFile(owner);
            pages.forEach((page, items) -> {
                String path = "pages." + page;
                yaml.set(path, null);
                for (int slot = 0; slot < items.length; slot++) {
                    ItemStack item = items[slot];
                    if (item != null && !item.isEmpty()) yaml.set(path + "." + slot, item);
                }
            });

            Files.createDirectories(folder);
            Path target = file(owner);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to save vault of " + owner + ", will retry on the next change", e);
            dirty.merge(owner, new HashMap<>(pages), (newer, failed) -> {
                failed.putAll(newer);
                return failed;
            });
            return false;
        }
    }
}
