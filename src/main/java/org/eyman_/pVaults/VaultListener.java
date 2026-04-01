package org.eyman_.pVaults;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.UUID;

public class VaultListener implements Listener {
    private final PVaults plugin;

    public VaultListener(PVaults plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        UUID u = p.getUniqueId();
        if (!plugin.getVaultManager().openPages.containsKey(u)) return;

        if (e.getRawSlot() >= 45 && e.getRawSlot() <= 53) {
            e.setCancelled(true);
            int cur = plugin.getVaultManager().openPages.get(u);
            UUID target = plugin.getVaultManager().adminViewing.getOrDefault(u, u);

            if (e.getSlot() == 45 && cur > 1) {
                plugin.getVaultManager().openVault(p, Bukkit.getOfflinePlayer(target), cur - 1, plugin.getVaultManager().adminViewing.containsKey(u));
            } else if (e.getSlot() == 53) {
                plugin.getVaultManager().openVault(p, Bukkit.getOfflinePlayer(target), cur + 1, plugin.getVaultManager().adminViewing.containsKey(u));
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        if (!plugin.getVaultManager().switchingPages.contains(u)) {
            Integer page = plugin.getVaultManager().openPages.remove(u);
            if (page != null) {
                plugin.getVaultManager().saveVault((Player) e.getPlayer(), page);
                plugin.getVaultManager().adminViewing.remove(u);
            }
        }
    }
}