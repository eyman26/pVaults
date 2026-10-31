package org.eyman_.pVaults.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class PVaultsMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("pVaults");

    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("pvaults.json");
    private volatile VaultConfig config;
    private VaultStorage storage;
    private Vaults vaults;
    private int ticks;

    @Override
    public void onInitialize() {
        try {
            reloadConfig();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + configFile, e);
        }

        VaultCommands commands = new VaultCommands(this);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> commands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (storage != null && ++ticks >= config.saveIntervalTicks) {
                ticks = 0;
                storage.flushAll();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player.containerMenu instanceof VaultMenuBase) player.doCloseContainer();
            // Deferred: the player is still in the player list while this event fires.
            server.execute(() -> {
                if (storage != null) storage.unloadIfIdle(player.getUUID());
            });
        });
    }

    private void start(MinecraftServer server) {
        storage = new VaultStorage(server, server.getWorldPath(LevelResource.ROOT).resolve("pvaults"), LOGGER);
        vaults = new Vaults(this, server, storage, LOGGER);
    }

    private void stop(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof VaultMenuBase) player.doCloseContainer();
        }
        storage.shutdown();
        storage = null;
        vaults = null;
    }

    void reloadConfig() throws IOException {
        config = VaultConfig.load(configFile);
    }

    VaultConfig config() {
        return config;
    }

    Vaults vaults() {
        return vaults;
    }
}
