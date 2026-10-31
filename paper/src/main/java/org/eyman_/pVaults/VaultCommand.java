package org.eyman_.pVaults;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

final class VaultCommand implements TabExecutor {

    private static final String USE = "playervaults.use";
    private static final String ADMIN = "playervaults.admin";

    private final PVaults plugin;
    private final VaultManager vaults;

    VaultCommand(PVaults plugin, VaultManager vaults) {
        this.plugin = plugin;
        this.vaults = vaults;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        VaultSettings settings = plugin.settings();

        if (command.getName().equalsIgnoreCase("playervaults")) {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) return false;
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(settings.message("no-permission"));
                return true;
            }
            plugin.reload();
            sender.sendMessage(plugin.settings().message("reload"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(settings.message("players-only"));
            return true;
        }
        if (!player.hasPermission(USE)) {
            player.sendMessage(settings.message("no-permission"));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("view")) {
            if (!player.hasPermission(ADMIN)) {
                player.sendMessage(settings.message("no-permission"));
                return true;
            }
            // getOfflinePlayer(String) can block on a Mojang lookup; never do that on a region thread.
            Player online = Bukkit.getPlayerExact(args[1]);
            OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                player.sendMessage(settings.message("unknown-player", Placeholder.unparsed("name", args[1])));
                return true;
            }
            String name = target.getName() != null ? target.getName() : args[1];
            vaults.open(player, target.getUniqueId(), name, args.length >= 3 ? parsePage(args[2]) : 1, true);
            return true;
        }

        vaults.open(player, player.getUniqueId(), player.getName(), args.length >= 1 ? parsePage(args[0]) : 1, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("playervaults")) {
            return args.length == 1 && sender.hasPermission(ADMIN) ? filter(List.of("reload"), args[0]) : List.of();
        }
        if (!(sender instanceof Player player)) return List.of();

        boolean admin = player.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> options = new ArrayList<>(pages(VaultManager.maxPages(player, plugin.settings())));
            if (admin) options.add("view");
            return filter(options, args[0]);
        }
        if (admin && args[0].equalsIgnoreCase("view")) {
            if (args.length == 2) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
            if (args.length == 3) return filter(pages(VaultSettings.MAX_PAGES), args[2]);
        }
        return List.of();
    }

    private static List<String> pages(int max) {
        return IntStream.rangeClosed(1, max).mapToObj(Integer::toString).toList();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static int parsePage(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
