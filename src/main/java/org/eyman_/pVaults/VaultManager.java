package org.eyman_.pVaults;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VaultManager {
    private final PVaults plugin;
    private final File vaultFolder;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final boolean isFolia = Bukkit.getVersion().contains("Folia");

    public final Map<UUID, Integer> openPages = new ConcurrentHashMap<>();
    public final Set<UUID> switchingPages = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public final Map<UUID, UUID> adminViewing = new ConcurrentHashMap<>();

    // Authoritative in-memory cache for anti-dupe: source of truth during session.
    private final Map<UUID, Map<Integer, ItemStack[]>> memoryCache = new ConcurrentHashMap<>();

    // Coalesces disk writes to prevent race conditions on Folia/Async IO.
    private final Map<String, ItemStack[]> pendingDiskWrite = new ConcurrentHashMap<>();
    private final Set<String> diskWriteInProgress = ConcurrentHashMap.newKeySet();

    // Handles for the per-viewer periodic reconciliation task.
    private interface TaskHandle { void cancel(); }
    private final Map<UUID, TaskHandle> reconcileTasks = new ConcurrentHashMap<>();

    public VaultManager(PVaults plugin) {
        this.plugin = plugin;
        this.vaultFolder = new File(plugin.getDataFolder(), "vaults");
        if (!vaultFolder.exists()) vaultFolder.mkdirs();
    }

    // Marker interface for authenticating valid vault GUIs.
    public static class VaultHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
        void setInventory(Inventory inventory) { this.inventory = inventory; }
    }

    public void openVault(Player viewer, OfflinePlayer target, int page, boolean isAdmin) {
        int max = getMaxPages(target);
        if (page < 1 || page > max) {
            viewer.sendMessage(mm.deserialize(getPrefix() + "<red>Invalid page. Max available: " + max));
            return;
        }

        UUID viewerUUID = viewer.getUniqueId();
        UUID targetUUID = target.getUniqueId();
        switchingPages.add(viewerUUID);

        if (openPages.containsKey(viewerUUID)) {
            syncVaultState(viewer, openPages.get(viewerUUID));
        }

        openPages.put(viewerUUID, page);
        if (isAdmin) adminViewing.put(viewerUUID, targetUUID);
        else adminViewing.remove(viewerUUID);

        String title = plugin.getConfig().getString("vault.page-title", "Vault")
                .replace("%player_name%", target.getName() != null ? target.getName() : "Unknown")
                .replace("%page%", String.valueOf(page))
                .replace("%max_pages%", String.valueOf(max));

        VaultHolder holder = new VaultHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, mm.deserialize(title));
        holder.setInventory(inv);

        ItemStack[] cached = getCachedPage(targetUUID, page);
        if (cached != null) {
            for (int i = 0; i < 45; i++) {
                if (cached[i] != null) inv.setItem(i, cached[i].clone());
            }
            addNavigationArrows(inv, page, max);
            viewer.openInventory(inv);
            startReconciliation(viewer);
            runTaskLater(viewer, () -> switchingPages.remove(viewerUUID));
            return;
        }

        runAsync(() -> {
            ItemStack[] loaded = new ItemStack[45];
            File f = new File(vaultFolder, targetUUID + ".yml");
            if (f.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection section = cfg.getConfigurationSection("pages." + page);
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        int slot = Integer.parseInt(key);
                        if (slot >= 0 && slot < 45) loaded[slot] = section.getItemStack(key);
                    }
                }
            }
            runSync(viewer, () -> {
                for (int i = 0; i < 45; i++) {
                    if (loaded[i] != null) inv.setItem(i, loaded[i]);
                }
                memoryCache.computeIfAbsent(targetUUID, k -> new ConcurrentHashMap<>()).put(page, loaded);
                addNavigationArrows(inv, page, max);
                viewer.openInventory(inv);
                startReconciliation(viewer);
                runTaskLater(viewer, () -> switchingPages.remove(viewerUUID));
            });
        });
    }

    // Captures live GUI state, updates memory cache, and queues async disk persistence.
    public void syncVaultState(Player viewer, int page) {
        UUID viewerUUID = viewer.getUniqueId();
        UUID targetUUID = adminViewing.getOrDefault(viewerUUID, viewerUUID);
        Inventory inv = viewer.getOpenInventory().getTopInventory();

        ItemStack[] snapshot = new ItemStack[45];
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            snapshot[i] = (it == null) ? null : it.clone();
        }

        memoryCache.computeIfAbsent(targetUUID, k -> new ConcurrentHashMap<>()).put(page, snapshot);
        queueDiskWrite(targetUUID, page, snapshot);
    }

    private ItemStack[] getCachedPage(UUID targetUUID, int page) {
        Map<Integer, ItemStack[]> pages = memoryCache.get(targetUUID);
        return (pages == null) ? null : pages.get(page);
    }

    private void queueDiskWrite(UUID targetUUID, int page, ItemStack[] snapshot) {
        String key = targetUUID + ":" + page;
        pendingDiskWrite.put(key, snapshot);
        if (diskWriteInProgress.add(key)) {
            runAsync(() -> drainDiskWrite(targetUUID, page, key));
        }
    }

    private void drainDiskWrite(UUID targetUUID, int page, String key) {
        ItemStack[] snapshot;
        while ((snapshot = pendingDiskWrite.remove(key)) != null) {
            writeToDisk(targetUUID, page, snapshot);
        }
        diskWriteInProgress.remove(key);
        if (pendingDiskWrite.containsKey(key) && diskWriteInProgress.add(key)) {
            runAsync(() -> drainDiskWrite(targetUUID, page, key));
        }
    }

    private void writeToDisk(UUID targetUUID, int page, ItemStack[] items) {
        File f = new File(vaultFolder, targetUUID + ".yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        cfg.set("pages." + page, null);
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].getType() != Material.AIR) {
                cfg.set("pages." + page + "." + i, items[i]);
            }
        }
        try { cfg.save(f); } catch (IOException ignored) {}
    }

    // Periodic task to self-heal and ensure synchronization for open vaults.
    private void startReconciliation(Player viewer) {
        UUID u = viewer.getUniqueId();
        if (reconcileTasks.containsKey(u)) return;
        long periodTicks = Math.max(1L, plugin.getConfig().getLong("vault.reconcile-interval-ticks", 20L));

        if (isFolia) {
            ScheduledTask task = viewer.getScheduler().runAtFixedRate(plugin, t -> reconcile(viewer), null, periodTicks, periodTicks);
            reconcileTasks.put(u, task::cancel);
        } else {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> reconcile(viewer), periodTicks, periodTicks);
            reconcileTasks.put(u, task::cancel);
        }
    }

    public void stopReconciliation(UUID u) {
        TaskHandle handle = reconcileTasks.remove(u);
        if (handle != null) handle.cancel();
    }

    private void reconcile(Player viewer) {
        UUID u = viewer.getUniqueId();
        Integer page = openPages.get(u);
        if (page == null || !viewer.isOnline()) {
            stopReconciliation(u);
            return;
        }
        if (!(viewer.getOpenInventory().getTopInventory().getHolder() instanceof VaultHolder)) {
            openPages.remove(u);
            adminViewing.remove(u);
            stopReconciliation(u);
            return;
        }
        syncVaultState(viewer, page);
    }

    private void addNavigationArrows(Inventory inv, int page, int max) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(m -> m.displayName(mm.deserialize(" ")));
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        if (page > 1) inv.setItem(45, createArrow("<red>Previous Page"));
        if (page < max) inv.setItem(53, createArrow("<green>Next Page"));
    }

    private ItemStack createArrow(String name) {
        ItemStack i = new ItemStack(Material.ARROW);
        i.editMeta(m -> m.displayName(mm.deserialize(name)));
        return i;
    }

    public int getMaxPages(OfflinePlayer p) {
        int def = plugin.getConfig().getInt("vault.default-max-pages", 2);
        if (!p.isOnline() || p.getPlayer() == null) return def;
        for (int i = 50; i > def; i--) {
            if (p.getPlayer().hasPermission("playervaults.pages." + i)) return i;
        }
        return def;
    }

    public String getPrefix() { return plugin.getConfig().getString("messages.prefix", ""); }
    public MiniMessage getMM() { return mm; }

    private void runAsync(Runnable r) {
        if (isFolia) Bukkit.getAsyncScheduler().runNow(plugin, t -> r.run());
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }
    private void runSync(Player p, Runnable r) {
        if (isFolia) p.getScheduler().run(plugin, t -> r.run(), null);
        else Bukkit.getScheduler().runTask(plugin, r);
    }
    private void runTaskLater(Player p, Runnable r) {
        if (isFolia) p.getScheduler().runDelayed(plugin, t -> r.run(), null, 2L);
        else Bukkit.getScheduler().runTaskLater(plugin, r, 2L);
    }
}
