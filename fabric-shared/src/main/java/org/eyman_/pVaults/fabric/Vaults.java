package org.eyman_.pVaults.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.OptionalInt;
import java.util.UUID;

/** Opening and navigating vault menus. Everything here runs on the server thread. */
final class Vaults {

    static final int MAX_PAGES = 50;
    // Looked up by id: 26.x moved the dyed items into Items.STAINED_GLASS_PANE (a ColorCollection).
    private static final Item FILLER = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("gray_stained_glass_pane"));

    record View(ServerPlayer viewer, UUID owner, String ownerName, int maxPages, boolean admin) {}

    private final PVaultsMod mod;
    private final MinecraftServer server;
    private final VaultStorage storage;
    private final Logger logger;

    Vaults(PVaultsMod mod, MinecraftServer server, VaultStorage storage, Logger logger) {
        this.mod = mod;
        this.server = server;
        this.storage = storage;
        this.logger = logger;
    }

    void open(ServerPlayer viewer, UUID owner, String ownerName, int page, boolean admin) {
        VaultConfig config = mod.config();
        int max = admin ? MAX_PAGES : maxPages(viewer, config);
        if (page < 1 || page > max) {
            viewer.sendSystemMessage(config.message("invalid-page", "%max%", Integer.toString(max)));
            return;
        }

        View view = new View(viewer, owner, ownerName, max, admin);
        storage.get(owner).whenCompleteAsync((vault, error) -> {
            if (error != null) {
                logger.error("Could not load vault of {}", owner, error);
                viewer.sendSystemMessage(config.message("load-failed"));
                return;
            }
            if (viewer.hasDisconnected()) return;

            VaultPage vaultPage = vault.page(page);
            // Count the viewer before openMenu closes their previous vault menu, otherwise
            // switching pages of an offline player's vault would unload it mid-switch.
            vault.viewers++;
            OptionalInt opened = viewer.openMenu(new SimpleMenuProvider(
                    (id, inventory, player) -> new VaultMenu(id, inventory, vaultPage, navigation(page, max), view, this),
                    config.title(ownerName, page, max)));
            if (opened.isEmpty()) {
                vault.viewers--;
                storage.unloadIfIdle(owner);
            }
        }, server);
    }

    void navigate(View view, int page) {
        if (page < 1 || page > view.maxPages()) return;
        // Defer so we are not swapping menus from inside the current menu's click handling.
        server.execute(() -> open(view.viewer(), view.owner(), view.ownerName(), page, view.admin()));
    }

    void onClosed(VaultMenuBase menu) {
        VaultStorage.Vault vault = menu.page.vault;
        vault.viewers--;
        storage.flush(vault);
        storage.unloadIfIdle(vault.owner);
    }

    static int maxPages(ServerPlayer player, VaultConfig config) {
        for (int pages = MAX_PAGES; pages > config.defaultMaxPages; pages--) {
            if (Permissions.check(player, "playervaults.pages." + pages, false)) return pages;
        }
        return config.defaultMaxPages;
    }

    private static SimpleContainer navigation(int page, int max) {
        SimpleContainer nav = new SimpleContainer(9);
        for (int slot = 0; slot < 9; slot++) nav.setItem(slot, named(new ItemStack(FILLER), " "));
        if (page > 1) nav.setItem(0, named(new ItemStack(Items.ARROW), "§cPrevious Page"));
        if (page < max) nav.setItem(8, named(new ItemStack(Items.ARROW), "§aNext Page"));
        return nav;
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
        return stack;
    }
}
