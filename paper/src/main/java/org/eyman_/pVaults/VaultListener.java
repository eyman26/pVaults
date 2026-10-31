package org.eyman_.pVaults;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;

import static org.eyman_.pVaults.VaultStorage.PAGE_SIZE;

final class VaultListener implements Listener {

    private final VaultManager vaults;

    VaultListener(VaultManager vaults) {
        this.vaults = vaults;
    }

    private static VaultManager.Session session(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof VaultManager.Session session ? session : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        VaultManager.Session session = session(event.getView());
        if (session == null) return;

        int raw = event.getRawSlot();
        if (raw >= PAGE_SIZE && raw < event.getView().getTopInventory().getSize()) {
            event.setCancelled(true);
            vaults.navigate(session, raw);
            return;
        }
        // Nav items never leave the GUI legitimately, so any copy of one in a click is a forged item.
        if (vaults.isNavItem(event.getCurrentItem()) || vaults.isNavItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (session(event.getView()) == null) return;
        for (int raw : event.getRawSlots()) {
            if (raw >= PAGE_SIZE && raw < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterClick(InventoryClickEvent event) {
        VaultManager.Session session = session(event.getView());
        if (session != null) vaults.queueSync(session);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDrag(InventoryDragEvent event) {
        VaultManager.Session session = session(event.getView());
        if (session != null) vaults.queueSync(session);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        VaultManager.Session session = session(event.getView());
        if (session != null) vaults.close(session);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        vaults.handleQuit(event.getPlayer());
    }
}
