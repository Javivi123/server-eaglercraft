package com.xavi.protect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HonorManager {

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, Integer> honorScores = new LinkedHashMap<String, Integer>(); // player.toLowerCase -> honor (-100 to 100)

    public HonorManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "honor.yml");
        load();
    }

    public synchronized void load() {
        honorScores.clear();
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (!file.exists()) {
                file.createNewFile();
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = cfg.getConfigurationSection("honor");
            if (sec != null) {
                for (String p : sec.getKeys(false)) {
                    int val = sec.getInt(p, 0);
                    honorScores.put(p.toLowerCase(), Math.max(-100, Math.min(100, val)));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[HonorManager] Error cargando honor.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (cfg == null) cfg = new YamlConfiguration();
            cfg.set("honor", null);
            for (Map.Entry<String, Integer> entry : honorScores.entrySet()) {
                cfg.set("honor." + entry.getKey(), entry.getValue());
            }
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[HonorManager] Error guardando honor.yml: " + e.getMessage());
        }
    }

    public int getHonor(String playerName) {
        if (playerName == null) return 0;
        Integer val = honorScores.get(playerName.toLowerCase());
        return val != null ? val : 0;
    }

    public void setHonor(String playerName, int val) {
        if (playerName == null) return;
        int clamped = Math.max(-100, Math.min(100, val));
        honorScores.put(playerName.toLowerCase(), clamped);
        save();
    }

    public void addHonor(Player player, int delta, String reason) {
        if (player == null) return;
        String name = player.getName();
        int current = getHonor(name);
        int updated = Math.max(-100, Math.min(100, current + delta));
        honorScores.put(name.toLowerCase(), updated);
        save();

        if (delta > 0) {
            // RDR2 High Honor Chime
            try {
                player.playSound(player.getLocation(), Sound.ORB_PICKUP, 1.0f, 1.8f);
                player.playSound(player.getLocation(), Sound.NOTE_PLING, 1.0f, 2.0f);
            } catch (Exception ignored) {}

            player.sendMessage(ChatColor.GOLD + "========================================");
            player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "▲ HONOR AUMENTADO (+" + delta + ") "
                    + ChatColor.YELLOW + getHonorBadge(updated));
            player.sendMessage(ChatColor.GRAY + "Motivo: " + ChatColor.WHITE + reason);
            player.sendMessage(ChatColor.GRAY + "Nivel actual: " + getHonorTitle(updated) + " (" + (updated > 0 ? "+" + updated : updated) + ")");
            player.sendMessage(getHonorBar(updated));
            player.sendMessage(ChatColor.GOLD + "========================================");
        } else if (delta < 0) {
            // RDR2 Low Honor Ominous Bass Rumble
            try {
                player.playSound(player.getLocation(), Sound.NOTE_BASS, 1.2f, 0.5f);
                player.playSound(player.getLocation(), Sound.WITHER_HURT, 0.6f, 0.7f);
            } catch (Exception ignored) {}

            player.sendMessage(ChatColor.DARK_RED + "========================================");
            player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "▼ HONOR DISMINUIDO (" + delta + ") "
                    + ChatColor.YELLOW + getHonorBadge(updated));
            player.sendMessage(ChatColor.GRAY + "Motivo: " + ChatColor.WHITE + reason);
            player.sendMessage(ChatColor.GRAY + "Nivel actual: " + getHonorTitle(updated) + " (" + (updated > 0 ? "+" + updated : updated) + ")");
            player.sendMessage(getHonorBar(updated));
            if (updated <= -40) {
                player.sendMessage(ChatColor.DARK_RED + "¡CUIDADO! Eres un FORAJIDO BUSCADO. ¡Otros jugadores obtendrán recompensas si te cazan!");
            }
            player.sendMessage(ChatColor.DARK_RED + "========================================");
        }
    }

    public String getHonorTitle(int honor) {
        if (honor >= 80) return ChatColor.GOLD + "" + ChatColor.BOLD + "🌟 HÉROE LEGENDARIO";
        if (honor >= 40) return ChatColor.YELLOW + "" + ChatColor.BOLD + "✨ FORASTERO JUSTO";
        if (honor >= 10) return ChatColor.GREEN + "🤠 BUEN SAMARITANO";
        if (honor > -10) return ChatColor.GRAY + "⚖️ NEUTRAL";
        if (honor > -40) return ChatColor.RED + "🗡️ BUSCAVIDAS DESPIADADO";
        if (honor > -80) return ChatColor.DARK_RED + "" + ChatColor.BOLD + "🔥 FORAJIDO PELIGROSO";
        return ChatColor.DARK_RED + "" + ChatColor.BOLD + "☠️ ENEMIGO PÚBLICO (SE BUSCA)";
    }

    public String getHonorBadge(int honor) {
        if (honor >= 80) return ChatColor.GOLD + "[🌟+" + honor + "]";
        if (honor >= 40) return ChatColor.YELLOW + "[✨+" + honor + "]";
        if (honor >= 10) return ChatColor.GREEN + "[🤠+" + honor + "]";
        if (honor > -10) return ChatColor.GRAY + "[⚖️" + (honor >= 0 ? "+" + honor : honor) + "]";
        if (honor > -40) return ChatColor.RED + "[🗡️" + honor + "]";
        if (honor > -80) return ChatColor.DARK_RED + "[🔥" + honor + "]";
        return ChatColor.DARK_RED + "[☠️" + honor + "]";
    }

    public String getHonorBar(int honor) {
        // Map -100..100 into a 16-step visual bar with dot at the position
        // Step 0: -100, Step 8: 0, Step 16: +100
        int pos = (int) Math.round(((honor + 100.0) / 200.0) * 16.0);
        if (pos < 0) pos = 0;
        if (pos > 16) pos = 16;

        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.DARK_GRAY).append("[ ");
        sb.append(ChatColor.DARK_RED).append("💀 ");

        for (int i = 0; i <= 16; i++) {
            if (i == pos) {
                if (honor >= 40) {
                    sb.append(ChatColor.YELLOW).append("⚪");
                } else if (honor >= 10) {
                    sb.append(ChatColor.GREEN).append("⚪");
                } else if (honor > -10) {
                    sb.append(ChatColor.WHITE).append("⚪");
                } else if (honor > -40) {
                    sb.append(ChatColor.RED).append("⚪");
                } else {
                    sb.append(ChatColor.DARK_RED).append("⚪");
                }
            } else {
                if (i < 8) {
                    sb.append(ChatColor.RED).append("━");
                } else if (i == 8) {
                    sb.append(ChatColor.GRAY).append("│");
                } else {
                    sb.append(ChatColor.GREEN).append("━");
                }
            }
        }

        sb.append(" ").append(ChatColor.GOLD).append("🌟 ");
        sb.append(ChatColor.DARK_GRAY).append("] ");
        sb.append(ChatColor.GRAY).append("(").append(honor > 0 ? "+" + honor : honor).append(")");
        return sb.toString();
    }

    public boolean isOutlaw(String playerName) {
        return getHonor(playerName) <= -40;
    }

    public boolean isHero(String playerName) {
        return getHonor(playerName) >= 40;
    }

    public Map<String, Integer> getAllHonorScores() {
        return new LinkedHashMap<String, Integer>(honorScores);
    }
}
