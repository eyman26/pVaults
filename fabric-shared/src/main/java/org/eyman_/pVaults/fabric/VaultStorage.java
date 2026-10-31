package org.eyman_.pVaults.fabric;

import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loaded vaults live on the server thread; file IO runs on a single background thread.
 * Because that thread is FIFO, a read queued after a write to the same file always sees it.
 */
final class VaultStorage {

    private final MinecraftServer server;
    private final Path folder;
    private final Logger logger;
    private final RegistryOps<Tag> ops;
    private final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("pVaults-io").daemon().factory());

    private final Map<UUID, Vault> loaded = new HashMap<>();
    private final Map<UUID, CompletableFuture<Vault>> loading = new HashMap<>();

    final class Vault {
        final UUID owner;
        private final CompoundTag pages;
        private final Int2ObjectMap<VaultPage> live = new Int2ObjectOpenHashMap<>();
        int viewers;
        volatile boolean writeFailed;

        private Vault(UUID owner, CompoundTag pages) {
            this.owner = owner;
            this.pages = pages;
        }

        VaultPage page(int number) {
            VaultPage page = live.get(number);
            if (page != null) return page;

            page = new VaultPage(this, number);
            for (Tag tag : pages.getListOrEmpty(Integer.toString(number))) {
                VaultPage target = page;
                ItemStackWithSlot.CODEC.parse(ops, tag)
                        .resultOrPartial(error -> logger.warn("Dropping unreadable item in vault {} page {}: {}", owner, number, error))
                        .filter(entry -> entry.slot() >= 0 && entry.slot() < VaultPage.SIZE)
                        .ifPresent(entry -> target.setItem(entry.slot(), entry.stack()));
            }
            page.dirty = false;
            live.put(number, page);
            return page;
        }
    }

    VaultStorage(MinecraftServer server, Path folder, Logger logger) {
        this.server = server;
        this.folder = folder;
        this.logger = logger;
        RegistryAccess registries = server.registryAccess();
        this.ops = registries.createSerializationContext(NbtOps.INSTANCE);
    }

    /** Server thread only. The future always completes on the server thread. */
    CompletableFuture<Vault> get(UUID owner) {
        Vault vault = loaded.get(owner);
        if (vault != null) return CompletableFuture.completedFuture(vault);

        return loading.computeIfAbsent(owner, id -> {
            CompletableFuture<Vault> future = CompletableFuture.supplyAsync(() -> read(id), io)
                    .thenApplyAsync(pages -> {
                        Vault created = new Vault(id, pages);
                        loaded.put(id, created);
                        return created;
                    }, server);
            future.whenCompleteAsync((ignored, error) -> loading.remove(id), server);
            return future;
        });
    }

    /** Encodes dirty pages and queues a write. Server thread only. */
    void flush(Vault vault) {
        boolean changed = false;
        for (VaultPage page : vault.live.values()) {
            if (!page.dirty) continue;
            vault.pages.put(Integer.toString(page.number), encode(page));
            page.dirty = false;
            changed = true;
        }
        if (!changed && !vault.writeFailed) return;
        vault.writeFailed = false;

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);
        root.put("pages", vault.pages.copy());
        io.execute(() -> write(vault, root));
    }

    void flushAll() {
        loaded.values().forEach(this::flush);
    }

    void unloadIfIdle(UUID owner) {
        Vault vault = loaded.get(owner);
        if (vault == null || vault.viewers > 0 || vault.writeFailed || server.getPlayerList().getPlayer(owner) != null) return;
        flush(vault);
        loaded.remove(owner);
    }

    void shutdown() {
        flushAll();
        loaded.clear();
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.error("Timed out waiting for vault saves to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ListTag encode(VaultPage page) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < VaultPage.SIZE; slot++) {
            ItemStack stack = page.getItem(slot);
            if (stack.isEmpty()) continue;
            ItemStackWithSlot.CODEC.encodeStart(ops, new ItemStackWithSlot(slot, stack))
                    .resultOrPartial(error -> logger.error("Could not save item in vault {} page {}: {}", page.vault.owner, page.number, error))
                    .ifPresent(list::add);
        }
        return list;
    }

    private Path file(UUID owner) {
        return folder.resolve(owner + ".dat");
    }

    private CompoundTag read(UUID owner) {
        Path file = file(owner);
        if (Files.notExists(file)) return new CompoundTag();
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag pages = root.getCompoundOrEmpty("pages");
            int version = root.getIntOr("DataVersion", dataVersion);
            return version < dataVersion ? upgrade(pages, version) : pages;
        } catch (IOException e) {
            // Failing the load is deliberate: carrying on with an empty vault would overwrite the real file.
            throw new UncheckedIOException("Could not read vault file " + file, e);
        }
    }

    private CompoundTag upgrade(CompoundTag pages, int fromVersion) {
        CompoundTag upgraded = new CompoundTag();
        for (String key : pages.keySet()) {
            ListTag fixed = new ListTag();
            for (Tag item : pages.getListOrEmpty(key)) {
                fixed.add(server.getFixerUpper()
                        .update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, item), fromVersion, dataVersion)
                        .getValue());
            }
            upgraded.put(key, fixed);
        }
        return upgraded;
    }

    private void write(Vault vault, CompoundTag root) {
        Path target = file(vault.owner);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(folder);
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to save vault {}, retrying on the next save cycle", vault.owner, e);
            vault.writeFailed = true;
        }
    }
}
