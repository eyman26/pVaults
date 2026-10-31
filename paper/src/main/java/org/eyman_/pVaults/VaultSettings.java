package org.eyman_.pVaults;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable view of config.yml. Listeners run on many region threads under Folia,
 * so they read this snapshot instead of the (non thread-safe) FileConfiguration.
 */
record VaultSettings(String prefix, Map<String, String> messages, int defaultPages, String titleFormat, long reconcileTicks) {

    static final int MAX_PAGES = 50;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "reload", "<green>Configuration reloaded.</green>",
            "no-permission", "<red>You do not have permission to do that.</red>",
            "players-only", "<red>Only players can use vaults.</red>",
            "invalid-page", "<red>Invalid page. Pages available: 1-<max>.</red>",
            "page-in-use", "<red>That vault page is already open somewhere else.</red>",
            "unknown-player", "<red>No player named <name> has played on this server.</red>"
    );

    static VaultSettings from(FileConfiguration config) {
        Map<String, String> messages = new HashMap<>(DEFAULT_MESSAGES);
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                messages.put(key, section.getString(key, ""));
            }
        }
        return new VaultSettings(
                messages.getOrDefault("prefix", ""),
                Map.copyOf(messages),
                Math.clamp(config.getInt("vault.default-max-pages", 2), 1, MAX_PAGES),
                config.getString("vault.page-title", "<gold>%player_name%'s Vault</gold> <gray>(%page%/%max_pages%)</gray>"),
                Math.max(1L, config.getLong("vault.reconcile-interval-ticks", 20L))
        );
    }

    Component message(String key, TagResolver... resolvers) {
        return MM.deserialize(prefix + messages.getOrDefault(key, key), resolvers);
    }

    Component title(String ownerName, int page, int maxPages) {
        return MM.deserialize(titleFormat
                .replace("%player_name%", ownerName)
                .replace("%page%", Integer.toString(page))
                .replace("%max_pages%", Integer.toString(maxPages)));
    }
}
