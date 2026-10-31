package org.eyman_.pVaults;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static org.eyman_.pVaults.VaultStorage.PAGE_SIZE;

/**
 * Owns every open vault GUI.
 * <p>
 * Threading: a session's inventory is only ever touched from its viewer's entity scheduler
 * (the viewer's region thread on Folia, the main thread on Paper). A page can be open by one
 * viewer at a time (see {@link #leases}); that is what makes two region threads editing the
 * same page impossible, and with it the classic "owner and admin both have it open" dupe.
 */
final class VaultManager {

    static final int PREV_SLOT = 45;
    static final int NEXT_SLOT = 53;
    private static final int SIZE = 54;

    private final PVaults plugin;
    private final VaultStorage storage;
    private final NamespacedKey navKey;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<PageKey, UUID> leases = new ConcurrentHashMap<>();
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    record PageKey(UUID owner, int page) {}

    static final class Session implements InventoryHolder {
        final Player viewer;
        final PageKey key;
        final String ownerName;
        final int maxPages;
        final boolean admin;
        Inventory inventory;
        ScheduledTask reconcileTask;
        boolean syncQueued;

        Session(Player viewer, PageKey key, String ownerName, int maxPages, boolean admin) {
            this.viewer = viewer;
            this.key = key;
            this.ownerName = ownerName;
            this.maxPages = maxPages;
            this.admin = admin;
        }

        int page() {
            return key.page();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    VaultManager(PVaults plugin, VaultStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.navKey = new NamespacedKey(plugin, "nav");
    }

    /** Must be called from the viewer's thread. */
    void open(Player viewer, UUID owner, String ownerName, int page, boolean admin) {
        VaultSettings settings = plugin.settings();
        int max = admin ? VaultSettings.MAX_PAGES : maxPages(viewer, settings);
        if (page < 1 || page > max) {
            viewer.sendMessage(settings.message("invalid-page", Placeholder.unparsed("max", Integer.toString(max))));
            return;
        }

        UUID viewerId = viewer.getUniqueId();
        PageKey key = new PageKey(owner, page);
        Session current = sessions.get(viewerId);
        if (current != null && current.key.equals(key)) return;
        if (!opening.add(viewerId)) return;

        UUID holder = leases.putIfAbsent(key, viewerId);
        if (holder != null && !holder.equals(viewerId)) {
            opening.remove(viewerId);
            viewer.sendMessage(settings.message("page-in-use"));
            return;
        }

        Session session = new Session(viewer, key, ownerName, max, admin);
        Runnable abort = () -> {
            leases.remove(key, viewerId);
            opening.remove(viewerId);
        };

        storage.load(owner, page).whenComplete((items, error) -> {
            boolean scheduled = viewer.getScheduler().execute(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not load vault page " + page + " of " + owner, error);
                    abort.run();
                    viewer.sendMessage(Component.text("Your vault could not be loaded, please tell an admin.", NamedTextColor.RED));
                    return;
                }
                show(session, items);
            }, abort, 1L);
            if (!scheduled) abort.run();
        });
    }

    private void show(Session session, ItemStack[] items) {
        Player viewer = session.viewer;
        UUID viewerId = viewer.getUniqueId();
        opening.remove(viewerId);
        if (!viewer.isOnline()) {
            leases.remove(session.key, viewerId);
            return;
        }

        VaultSettings settings = plugin.settings();
        Inventory inventory = Bukkit.createInventory(session, SIZE, settings.title(session.ownerName, session.page(), session.maxPages));
        session.inventory = inventory;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            if (items[slot] != null) inventory.setItem(slot, items[slot].clone());
        }
        decorate(inventory, session.page(), session.maxPages);

        // Swap before opening: the close event fired for the old GUI then sees it is no longer the
        // active session and leaves it alone, since finish() already persisted it here.
        Session previous = sessions.put(viewerId, session);
        if (previous != null) finish(previous);

        viewer.openInventory(inventory);
        long period = settings.reconcileTicks();
        session.reconcileTask = viewer.getScheduler().runAtFixedRate(plugin, task -> reconcile(session), null, period, period);
    }

    void navigate(Session session, int slot) {
        int target = slot == PREV_SLOT ? session.page() - 1 : slot == NEXT_SLOT ? session.page() + 1 : -1;
        if (target < 1 || target > session.maxPages) return;
        open(session.viewer, session.key.owner(), session.ownerName, target, session.admin);
    }

    /** Snapshots the GUI one tick later, once the click/drag that triggered it has been applied. */
    void queueSync(Session session) {
        if (session.syncQueued) return;
        session.syncQueued = true;
        session.viewer.getScheduler().run(plugin, task -> {
            session.syncQueued = false;
            if (isActive(session)) snapshot(session);
        }, null);
    }

    void close(Session session) {
        if (sessions.remove(session.viewer.getUniqueId(), session)) {
            finish(session);
        }
    }

    void handleQuit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) close(session);

        UUID id = player.getUniqueId();
        if (leases.keySet().stream().noneMatch(key -> key.owner().equals(id))) {
            storage.evictIfIdle(id);
        }
    }

    void closeAll() {
        for (Session session : List.copyOf(sessions.values())) {
            close(session);
            session.viewer.closeInventory();
        }
    }

    boolean isNavItem(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(navKey, PersistentDataType.BYTE);
    }

    private boolean isActive(Session session) {
        return sessions.get(session.viewer.getUniqueId()) == session;
    }

    private void reconcile(Session session) {
        if (!isActive(session) || !session.viewer.isOnline()) {
            session.reconcileTask.cancel();
            return;
        }
        // A plugin or the server closed the GUI without a close event reaching us.
        if (session.viewer.getOpenInventory().getTopInventory().getHolder(false) != session) {
            close(session);
            return;
        }
        snapshot(session);
    }

    private void finish(Session session) {
        if (session.reconcileTask != null) session.reconcileTask.cancel();
        snapshot(session);
        leases.remove(session.key, session.viewer.getUniqueId());
    }

    private void snapshot(Session session) {
        ItemStack[] copy = new ItemStack[PAGE_SIZE];
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            ItemStack item = session.inventory.getItem(slot);
            if (item != null && !item.isEmpty()) copy[slot] = item.clone();
        }
        storage.store(session.key.owner(), session.page(), copy);
    }

    private void decorate(Inventory inventory, int page, int max) {
        ItemStack filler = navItem(Material.GRAY_STAINED_GLASS_PANE, Component.space());
        for (int slot = PAGE_SIZE; slot < SIZE; slot++) inventory.setItem(slot, filler);
        if (page > 1) inventory.setItem(PREV_SLOT, navItem(Material.ARROW, Component.text("Previous Page", NamedTextColor.RED)));
        if (page < max) inventory.setItem(NEXT_SLOT, navItem(Material.ARROW, Component.text("Next Page", NamedTextColor.GREEN)));
    }

    private ItemStack navItem(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(navKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    static int maxPages(Player player, VaultSettings settings) {
        for (int pages = VaultSettings.MAX_PAGES; pages > settings.defaultPages(); pages--) {
            if (player.hasPermission("playervaults.pages." + pages)) return pages;
        }
        return settings.defaultPages();
    }
}
