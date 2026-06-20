package org.eyman_.pVaults;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

public class VaultListener implements Listener {
    private final PVaults plugin;

    public VaultListener(PVaults plugin) {
        this.plugin = plugin;
    }

    // verifies the VaultHolder marker before acting
    @EventHandler
    public void onNavClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        UUID u = p.getUniqueId();
        if (!plugin.getVaultManager().openPages.containsKey(u)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof VaultManager.VaultHolder)) return;

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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickSync(InventoryClickEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        sync(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragSync(InventoryDragEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        sync(p);
    }

    private void sync(Player p) {
        UUID u = p.getUniqueId();
        Integer page = plugin.getVaultManager().openPages.get(u);
        if (page == null) return;
        if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof VaultManager.VaultHolder)) return;
        plugin.getVaultManager().syncVaultState(p, page);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        UUID u = p.getUniqueId();

        // openVault() closes the old GUI itself in order to open the new page, but
        // it has already flushed state and updated openPages/adminViewing for the NEW page before
        // that happens. Treat this close as a no-op so we don't clobber the page we just switched to.
        if (plugin.getVaultManager().switchingPages.contains(u)) return;

        Integer page = plugin.getVaultManager().openPages.remove(u);
        if (page != null) {
            // final authoritative sync of whatever is in the gui right now, then stop tracking.
            if (e.getView().getTopInventory().getHolder() instanceof VaultManager.VaultHolder) {
                plugin.getVaultManager().syncVaultState(p, page);
            }
            plugin.getVaultManager().adminViewing.remove(u);
            plugin.getVaultManager().stopReconciliation(u);

            returnCursorItem(p);
        }
    }

    /**
     * safety net for disconnects. Bukkit normally fires InventoryCloseEvent
     * before a player fully quits, but this guarantees a session can never be left dangling
     * with a stale openPages entry, a leaked reconciliation task, or an item parked on the cursor
     * if a disconnect ever short-circuits the normal close flow.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();

        Integer page = plugin.getVaultManager().openPages.remove(u);
        if (page != null) {
            // Only sync if the player is still actually looking at a vault GUI right now if the
            // close already happened the top inventory will be their own
            // inventory by this point, and snapshotting that into vault data would be wrong.
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof VaultManager.VaultHolder) {
                plugin.getVaultManager().syncVaultState(p, page);
            }
            plugin.getVaultManager().adminViewing.remove(u);
            plugin.getVaultManager().stopReconciliation(u);
        }

        returnCursorItem(p);
    }

    /** clears whatever is on the player's cursor and puts it back in their
     *  inventory, dropping it at their feet only if their inventory is completely full. */
    private void returnCursorItem(Player p) {
        ItemStack cursor = p.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        p.setItemOnCursor(null);
        Map<Integer, ItemStack> overflow = p.getInventory().addItem(cursor);
        for (ItemStack remaining : overflow.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), remaining);
        }
    }
}
