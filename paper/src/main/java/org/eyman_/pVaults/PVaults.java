package org.eyman_.pVaults;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PVaults extends JavaPlugin {

    private volatile VaultSettings settings;
    private VaultStorage storage;
    private VaultManager vaults;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = VaultSettings.from(getConfig());
        storage = new VaultStorage(getDataFolder().toPath().resolve("vaults"), getLogger());
        vaults = new VaultManager(this, storage);

        VaultCommand command = new VaultCommand(this, vaults);
        for (String name : new String[]{"vault", "playervaults"}) {
            PluginCommand pluginCommand = getCommand(name);
            if (pluginCommand != null) pluginCommand.setExecutor(command);
        }
        getServer().getPluginManager().registerEvents(new VaultListener(vaults), this);
    }

    @Override
    public void onDisable() {
        if (vaults != null) vaults.closeAll();
        if (storage != null) storage.shutdown();
    }

    void reload() {
        reloadConfig();
        settings = VaultSettings.from(getConfig());
    }

    VaultSettings settings() {
        return settings;
    }
}
