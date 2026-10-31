package org.eyman_.pVaults.fabric;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

/**
 * One live vault page. Every viewer of the page shares this container (just like a chest),
 * so there is never a second copy of the items that could drift and duplicate.
 */
final class VaultPage extends SimpleContainer {

    static final int SIZE = 45;

    final VaultStorage.Vault vault;
    final int number;
    boolean dirty;

    VaultPage(VaultStorage.Vault vault, int number) {
        super(SIZE);
        this.vault = vault;
        this.number = number;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        dirty = true;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
