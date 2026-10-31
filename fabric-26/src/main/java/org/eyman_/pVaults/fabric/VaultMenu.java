package org.eyman_.pVaults.fabric;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

final class VaultMenu extends VaultMenuBase {

    VaultMenu(int containerId, Inventory playerInventory, VaultPage page, Container nav, Vaults.View view, Vaults vaults) {
        super(containerId, playerInventory, page, nav, view, vaults);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (interceptNav(slotId, input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) return;
        super.clicked(slotId, button, input, player);
    }
}
