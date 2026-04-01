package org.eyman_.pVaults;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class PVaults extends JavaPlugin {
    private VaultManager vaultManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.vaultManager = new VaultManager(this);

        VaultCommand vaultCommand = new VaultCommand(this);
        if (getCommand("vault") != null) getCommand("vault").setExecutor(vaultCommand);
        if (getCommand("playervaults") != null) getCommand("playervaults").setExecutor(vaultCommand);

        Bukkit.getPluginManager().registerEvents(new VaultListener(this), this);

        getLogger().info("pVaults Enabled.");
    }

    public VaultManager getVaultManager() {
        return vaultManager;
    }
}