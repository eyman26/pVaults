package org.eyman_.pVaults;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class VaultCommand implements CommandExecutor {
    private final PVaults plugin;

    public VaultCommand(PVaults plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("vault")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use vault commands.");
                return true;
            }

            // Check basic usage permission
            if (!player.hasPermission("playervaults.use")) {
                player.sendMessage(plugin.getVaultManager().getMM().deserialize(
                        plugin.getVaultManager().getPrefix() + "<red>You don't have permission to use vaults."));
                return true;
            }

            // ADMIN VIEW CHECK: Only OPs/Admins can use /vault view <player>
            if (args.length >= 2 && args[0].equalsIgnoreCase("view")) {
                if (!player.hasPermission("playervaults.admin")) {
                    player.sendMessage(plugin.getVaultManager().getMM().deserialize(
                            plugin.getVaultManager().getPrefix() + "<red>Only administrators can view other players' vaults."));
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                int page = (args.length >= 3) ? parse(args[2]) : 1;

                // Open target vault as Admin
                plugin.getVaultManager().openVault(player, target, page, true);
                return true;
            }

            // NORMAL USAGE: /vault [page]
            int page = (args.length == 1) ? parse(args[0]) : 1;
            plugin.getVaultManager().openVault(player, player, page, false);
            return true;
        }

        // RELOAD COMMAND: /playervaults reload
        if (cmd.getName().equalsIgnoreCase("playervaults") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("playervaults.admin")) {
                plugin.reloadConfig();
                sender.sendMessage(plugin.getVaultManager().getMM().deserialize(
                        plugin.getVaultManager().getPrefix() + "<green>Config Reloaded!"));
            } else {
                sender.sendMessage(plugin.getVaultManager().getMM().deserialize(
                        plugin.getVaultManager().getPrefix() + "<red>No permission."));
            }
            return true;
        }
        return false;
    }

    private int parse(String s) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}