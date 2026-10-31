package org.eyman_.pVaults.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Gson-mapped config/pvaults.json. Colors use '&' codes. */
final class VaultConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    int defaultMaxPages = 2;
    String pageTitle = "&6%player_name%'s Vault &7(%page%/%max_pages%)";
    int saveIntervalTicks = 100;
    Map<String, String> messages = new LinkedHashMap<>(Map.of(
            "prefix", "&7[&6Vault&7] ",
            "reload", "&aConfiguration reloaded.",
            "invalid-page", "&cInvalid page. Pages available: 1-%max%.",
            "unknown-player", "&cNo player named %name% has played on this server.",
            "load-failed", "&cThat vault could not be loaded, please tell an admin."
    ));

    static VaultConfig load(Path file) throws IOException {
        VaultConfig config = new VaultConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                VaultConfig read = GSON.fromJson(reader, VaultConfig.class);
                if (read != null) config = read;
            } catch (JsonParseException e) {
                throw new IOException("Malformed " + file.getFileName(), e);
            }
        }
        config.defaultMaxPages = Math.clamp(config.defaultMaxPages, 1, Vaults.MAX_PAGES);
        config.saveIntervalTicks = Math.max(1, config.saveIntervalTicks);
        // Write back so options added in newer versions show up in old files.
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(config, writer);
        }
        return config;
    }

    Component message(String key, String... replacements) {
        String text = messages.getOrDefault("prefix", "") + messages.getOrDefault(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return colored(text);
    }

    Component title(String ownerName, int page, int maxPages) {
        return colored(pageTitle
                .replace("%player_name%", ownerName)
                .replace("%page%", Integer.toString(page))
                .replace("%max_pages%", Integer.toString(maxPages)));
    }

    static Component colored(String legacy) {
        return Component.literal(legacy.replace('&', '§'));
    }
}
