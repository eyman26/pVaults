package org.eyman_.pVaults.fabric;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 6-row chest menu: 45 slots backed by the shared {@link VaultPage}, a locked navigation row, then
 * the player inventory. The click override lives in the per-version {@code VaultMenu} because
 * {@code clicked}'s signature differs between 1.21.x and 26.x.
 */
abstract class VaultMenuBase extends AbstractContainerMenu {

    static final int NAV_START = VaultPage.SIZE;
    static final int NAV_END = NAV_START + 9;
    static final int PREV_SLOT = NAV_START;
    static final int NEXT_SLOT = NAV_END - 1;

    final VaultPage page;
    final Vaults.View view;
    private final Vaults vaults;
    private boolean closed;

    protected VaultMenuBase(int containerId, Inventory playerInventory, VaultPage page, Container nav, Vaults.View view, Vaults vaults) {
        super(MenuType.GENERIC_9x6, containerId);
        this.page = page;
        this.view = view;
        this.vaults = vaults;

        for (int index = 0; index < NAV_END; index++) {
            int x = 8 + (index % 9) * 18;
            int y = 18 + (index / 9) * 18;
            addSlot(index < NAV_START ? new Slot(page, index, x, y) : new LockedSlot(nav, index - NAV_START, x, y));
        }
        addStandardInventorySlots(playerInventory, 8, 140);
    }

    /** @return true if the click hit the nav row and must not reach vanilla handling. */
    protected boolean interceptNav(int slotId, boolean plainClick) {
        if (slotId < NAV_START || slotId >= NAV_END) return false;
        if (plainClick && (slotId == PREV_SLOT || slotId == NEXT_SLOT)) {
            vaults.navigate(view, page.number + (slotId == PREV_SLOT ? -1 : 1));
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = index < NAV_END
                ? moveItemStackTo(stack, NAV_END, slots.size(), true)
                : moveItemStackTo(stack, 0, NAV_START, false);
        if (!moved) return ItemStack.EMPTY;

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return !closed;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (closed) return;
        closed = true;
        vaults.onClosed(this);
    }

    private static final class LockedSlot extends Slot {
        LockedSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
