package org.eyman_.pVaults.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

final class VaultCommands {

    private final PVaultsMod mod;

    VaultCommands(PVaultsMod mod) {
        this.mod = mod;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vault")
                .requires(Permissions.require("playervaults.use", true))
                .executes(ctx -> openOwn(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, Vaults.MAX_PAGES))
                        .executes(ctx -> openOwn(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.literal("view")
                        .requires(Permissions.require("playervaults.admin", PermissionLevel.GAMEMASTERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                .executes(ctx -> view(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1, Vaults.MAX_PAGES))
                                        .executes(ctx -> view(ctx, IntegerArgumentType.getInteger(ctx, "page")))))));

        dispatcher.register(Commands.literal("playervaults")
                .requires(Permissions.require("playervaults.admin", PermissionLevel.GAMEMASTERS))
                .then(Commands.literal("reload").executes(this::reload)));
    }

    private int openOwn(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        mod.vaults().open(player, player.getUUID(), player.nameAndId().name(), page, false);
        return 1;
    }

    private int view(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        ServerPlayer viewer = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = ctx.getSource().getServer();

        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            mod.vaults().open(viewer, online.getUUID(), online.nameAndId().name(), page, true);
            return 1;
        }
        // The name cache can fall back to a Mojang lookup, which must not run on the server thread.
        CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name))
                .thenAcceptAsync(profile -> {
                    if (viewer.hasDisconnected()) return;
                    profile.ifPresentOrElse(
                            found -> mod.vaults().open(viewer, found.id(), found.name(), page, true),
                            () -> viewer.sendSystemMessage(mod.config().message("unknown-player", "%name%", name)));
                }, server);
        return 1;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            mod.reloadConfig();
            ctx.getSource().sendSuccess(() -> mod.config().message("reload"), true);
            return 1;
        } catch (IOException e) {
            ctx.getSource().sendFailure(VaultConfig.colored("&cReload failed: " + e.getMessage()));
            return 0;
        }
    }
}
