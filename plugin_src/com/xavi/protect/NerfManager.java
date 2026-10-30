package com.xavi.protect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NerfManager {

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, Boolean> nerfedPlayers = new LinkedHashMap<String, Boolean>();

    public NerfManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "nerf.yml");
        load();
    }

    public synchronized void load() {
        nerfedPlayers.clear();
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (!file.exists()) {
                file.createNewFile();
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = cfg.getConfigurationSection("nerfed");
            if (sec != null) {
                for (String p : sec.getKeys(false)) {
                    nerfedPlayers.put(p.toLowerCase(), sec.getBoolean(p, false));
                }
            }
            // ProtenGavila siempre pre-activado por defecto
            if (!nerfedPlayers.containsKey("protengavila")) {
                nerfedPlayers.put("protengavila", true);
                save();
            }
            plugin.getLogger().info("[NerfManager] Cargados " + nerfedPlayers.size() + " estados de nerfeo.");
        } catch (Exception e) {
            plugin.getLogger().warning("[NerfManager] Error cargando nerf.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (cfg == null) cfg = new YamlConfiguration();
            cfg.set("nerfed", null);
            for (Map.Entry<String, Boolean> entry : nerfedPlayers.entrySet()) {
                cfg.set("nerfed." + entry.getKey(), entry.getValue());
            }
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[NerfManager] Error guardando nerf.yml: " + e.getMessage());
        }
    }

    public boolean isNerfed(String name) {
        if (name == null) return false;
        Boolean val = nerfedPlayers.get(name.toLowerCase());
        return val != null && val;
    }

    public void setNerfed(String name, boolean nerfed) {
        if (name == null || name.trim().isEmpty()) return;
        String key = name.trim().toLowerCase();
        nerfedPlayers.put(key, nerfed);
        save();

        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(name)) {
                    p = online;
                    break;
                }
            }
        }

        if (p != null && p.isOnline()) {
            if (nerfed) {
                applyNerf(p);
                sendNerfMessage(p);
            } else {
                removeNerf(p);
                p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[NERF DESACTIVADO] "
                        + ChatColor.YELLOW + "¡Tus restricciones han sido eliminadas por el admin Xavi! Ahora juegas en modo normal.");
            }
        }
    }

    public boolean isLockedSlot(int slot) {
        // Ranuras 18 a 35 (18 ranuras = 50% de la capacidad de 36)
        return slot >= 18 && slot <= 35;
    }

    public boolean isLockedItem(ItemStack item) {
        if (item == null || item.getType() != Material.STAINED_GLASS_PANE) return false;
        if (item.getDurability() != (short) 14) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().contains("✖ BLOQUEADO");
    }

    public ItemStack createLockedItem() {
        ItemStack item = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 14);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✖ BLOQUEADO");
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Ranura bloqueada por nerfeo (50% inventario)");
        lore.add(ChatColor.DARK_RED + "No se puede mover ni tirar.");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public void enforceLockedSlots(Player p) {
        if (p == null || !p.isOnline()) return;
        PlayerInventory inv = p.getInventory();
        ItemStack locked = createLockedItem();

        for (int i = 18; i <= 35; i++) {
            ItemStack current = inv.getItem(i);
            if (current == null || current.getType() == Material.AIR) {
                inv.setItem(i, locked.clone());
            } else if (!isLockedItem(current)) {
                inv.setItem(i, locked.clone());
                Map<Integer, ItemStack> overflow = inv.addItem(current);
                for (ItemStack drop : overflow.values()) {
                    if (drop != null && drop.getType() != Material.AIR) {
                        p.getWorld().dropItemNaturally(p.getLocation(), drop);
                    }
                }
            }
        }
        p.updateInventory();
    }

    public void applyNerf(Player p) {
        if (p == null || !p.isOnline()) return;
        // 1. Vida a la mitad (10.0 = 5 corazones)
        p.setMaxHealth(10.0);
        if (p.getHealth() > 10.0) {
            p.setHealth(10.0);
        }

        // 2. Inventario a la mitad (bloquear ranuras 18 a 35)
        enforceLockedSlots(p);

        // 3. Mineria nerfeada (Fatiga I suave para balancear farmeo rápido)
        p.removePotionEffect(PotionEffectType.SLOW_DIGGING);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, 1000000, 0, false, false));
    }

    public void removeNerf(Player p) {
        if (p == null || !p.isOnline()) return;
        // 1. Restaurar vida completa (20.0 = 10 corazones)
        p.setMaxHealth(20.0);
        p.setHealth(20.0);

        // 2. Limpiar ranuras bloqueadas
        PlayerInventory inv = p.getInventory();
        for (int i = 18; i <= 35; i++) {
            ItemStack current = inv.getItem(i);
            if (isLockedItem(current)) {
                inv.setItem(i, null);
            }
        }
        p.updateInventory();

        // 3. Quitar fatiga
        p.removePotionEffect(PotionEffectType.SLOW_DIGGING);
    }

    public void sendNerfMessage(Player p) {
        p.sendMessage(ChatColor.DARK_RED + "========================================");
        p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "¡ATENCIÓN " + p.getName() + "!");
        p.sendMessage(ChatColor.YELLOW + "Has entrado al servidor con el " + ChatColor.GOLD + "" + ChatColor.BOLD + "MODO NERF ACTIVADO" + ChatColor.YELLOW + ":");
        p.sendMessage(ChatColor.RED + " • Vida máxima reducida al 50% (5 corazones)");
        p.sendMessage(ChatColor.RED + " • Capacidad de inventario al 50% (18 ranuras bloqueadas)");
        p.sendMessage(ChatColor.RED + " • Daño de ataque cuerpo a cuerpo reducido un 40%");
        p.sendMessage(ChatColor.RED + " • Experiencia ganada reducida al 50%");
        p.sendMessage(ChatColor.RED + " • Minería balanceada (Fatiga I)");
        p.sendMessage(ChatColor.AQUA + "El admin Xavi puede modificar o desactivar tu nerfeo desde su consola.");
        p.sendMessage(ChatColor.DARK_RED + "========================================");
    }

    public Map<String, Boolean> getAllNerfed() {
        return new LinkedHashMap<String, Boolean>(nerfedPlayers);
    }
}
