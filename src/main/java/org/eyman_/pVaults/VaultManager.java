package org.eyman_.pVaults;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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

    public VaultManager(PVaults plugin) {
        this.plugin = plugin;
        // This creates the "vaults" folder you need for transfers
        this.vaultFolder = new File(plugin.getDataFolder(), "vaults");
        if (!vaultFolder.exists()) vaultFolder.mkdirs();
    }

    public void openVault(Player viewer, OfflinePlayer target, int page, boolean isAdmin) {
        int max = getMaxPages(target);
        if (page < 1 || page > max) {
            viewer.sendMessage(mm.deserialize(getPrefix() + "<red>Invalid page. Max available: " + max));
            return;
        }

        UUID viewerUUID = viewer.getUniqueId();
        switchingPages.add(viewerUUID);

        if (openPages.containsKey(viewerUUID)) {
            saveVault(viewer, openPages.get(viewerUUID));
        }

        openPages.put(viewerUUID, page);
        if (isAdmin) adminViewing.put(viewerUUID, target.getUniqueId());

        String title = plugin.getConfig().getString("vault.page-title", "Vault")
                .replace("%player_name%", target.getName() != null ? target.getName() : "Unknown")
                .replace("%page%", String.valueOf(page))
                .replace("%max_pages%", String.valueOf(max));

        Inventory inv = Bukkit.createInventory(null, 54, mm.deserialize(title));

        runAsync(() -> {
            File f = new File(vaultFolder, target.getUniqueId() + ".yml");
            if (f.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection section = cfg.getConfigurationSection("pages." + page);
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        int slot = Integer.parseInt(key);
                        ItemStack item = section.getItemStack(key);
                        if (item != null) runSync(viewer, () -> inv.setItem(slot, item));
                    }
                }
            }
            runSync(viewer, () -> {
                addNavigationArrows(inv, page, max);
                viewer.openInventory(inv);
                runTaskLater(viewer, () -> switchingPages.remove(viewerUUID));
            });
        });
    }

    public void saveVault(Player viewer, int page) {
        UUID viewerUUID = viewer.getUniqueId();
        UUID targetUUID = adminViewing.getOrDefault(viewerUUID, viewerUUID);
        Inventory inv = viewer.getOpenInventory().getTopInventory();

        ItemStack[] itemsToSave = new ItemStack[45];
        for (int i = 0; i < 45; i++) {
            ItemStack it = inv.getItem(i);
            itemsToSave[i] = (it == null) ? null : it.clone();
        }

        runAsync(() -> {
            File f = new File(vaultFolder, targetUUID + ".yml");
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("pages." + page, null); // Clear old data
            for (int i = 0; i < 45; i++) {
                if (itemsToSave[i] != null && itemsToSave[i].getType() != Material.AIR) {
                    cfg.set("pages." + page + "." + i, itemsToSave[i]);
                }
            }
            try { cfg.save(f); } catch (IOException ignored) {}
        });
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