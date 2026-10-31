package org.eyman_.pVaults.fabric;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

final class VaultMenu extends VaultMenuBase {

    VaultMenu(int containerId, Inventory playerInventory, VaultPage page, Container nav, Vaults.View view, Vaults vaults) {
        super(containerId, playerInventory, page, nav, view, vaults);
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (interceptNav(slotId, type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) return;
        super.clicked(slotId, button, type, player);
    }
}
