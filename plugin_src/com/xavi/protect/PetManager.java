package com.xavi.protect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PetManager {

    public static class PetData {
        public String uuid;
        public String name;
        public String owner;
        public String entityType;
        public String world;
        public double x;
        public double y;
        public double z;
        public String claimedDate;

        public PetData(String uuid, String name, String owner, String entityType, String world, double x, double y, double z, String claimedDate) {
            this.uuid = uuid;
            this.name = name;
            this.owner = owner;
            this.entityType = entityType;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.claimedDate = claimedDate;
        }
    }

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, PetData> petsByUuid = new LinkedHashMap<String, PetData>();
    private final Map<String, PetData> petsByName = new LinkedHashMap<String, PetData>();

    public PetManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pets.yml");
        load();
    }

    public synchronized void load() {
        petsByUuid.clear();
        petsByName.clear();
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().warning("No se pudo crear pets.yml: " + e.getMessage());
            }
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("pets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String uuid = sec.getString(key + ".uuid", key);
                String name = sec.getString(key + ".name", key);
                String owner = sec.getString(key + ".owner", "Xavi");
                String entityType = sec.getString(key + ".type", "SHEEP");
                String world = sec.getString(key + ".world", "world");
                double x = sec.getDouble(key + ".x", -269.0);
                double y = sec.getDouble(key + ".y", 76.0);
                double z = sec.getDouble(key + ".z", -2.0);
                String claimedDate = sec.getString(key + ".claimedDate", "01/11/2026 18:00:00");

                PetData pd = new PetData(uuid, name, owner, entityType, world, x, y, z, claimedDate);
                if (uuid != null && !uuid.isEmpty()) {
                    petsByUuid.put(uuid.toLowerCase(), pd);
                }
                petsByName.put(name.toLowerCase(), pd);
            }
        }

        // Registrar a Josefa por defecto si aún no está en pets.yml
        if (!petsByName.containsKey("josefa")) {
            PetData josefa = new PetData("", "Josefa", "Xavi", "SHEEP", "world", -269.5, 76.0, -2.5, "01/11/2026 18:00:00");
            petsByName.put("josefa", josefa);
            save();
        }

        plugin.getLogger().info("[PetManager] Cargadas " + petsByName.size() + " mascotas protegidas desde pets.yml.");
    }

    public synchronized void save() {
        if (cfg == null) cfg = new YamlConfiguration();
        cfg.set("pets", null);

        for (PetData pd : petsByName.values()) {
            String key = pd.name.toLowerCase().replaceAll("[^a-zA-Z0-9_]", "");
            if (key.isEmpty()) key = "pet_" + System.currentTimeMillis();
            cfg.set("pets." + key + ".uuid", pd.uuid);
            cfg.set("pets." + key + ".name", pd.name);
            cfg.set("pets." + key + ".owner", pd.owner);
            cfg.set("pets." + key + ".type", pd.entityType);
            cfg.set("pets." + key + ".world", pd.world);
            cfg.set("pets." + key + ".x", pd.x);
            cfg.set("pets." + key + ".y", pd.y);
            cfg.set("pets." + key + ".z", pd.z);
            cfg.set("pets." + key + ".claimedDate", pd.claimedDate);
        }

        try {
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Error guardando pets.yml: " + e.getMessage());
        }
    }

    public boolean registerPet(LivingEntity entity, String name, String owner) {
        if (entity == null || name == null || name.trim().isEmpty()) return false;
        name = name.trim();

        // Configuración visual y física del animal en el mundo
        entity.setCustomName(ChatColor.GREEN + "" + ChatColor.BOLD + name);
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);
        if (entity instanceof Ageable) {
            ((Ageable) entity).setAgeLock(true);
        }

        String uuid = entity.getUniqueId().toString();
        String dateStr = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date());
        Location loc = entity.getLocation();

        PetData pd = new PetData(uuid, name, owner, entity.getType().name(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), dateStr);
        petsByUuid.put(uuid.toLowerCase(), pd);
        petsByName.put(name.toLowerCase(), pd);
        save();
        return true;
    }

    public boolean isProtectedPet(Entity e) {
        if (e == null) return false;
        if (e.getUniqueId() != null && petsByUuid.containsKey(e.getUniqueId().toString().toLowerCase())) {
            return true;
        }
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            String cName = le.getCustomName();
            if (cName != null) {
                String clean = ChatColor.stripColor(cName).trim().toLowerCase();
                if (petsByName.containsKey(clean)) {
                    return true;
                }
            }
        }
        return false;
    }

    public PetData getPetData(Entity e) {
        if (e == null) return null;
        if (e.getUniqueId() != null) {
            PetData pd = petsByUuid.get(e.getUniqueId().toString().toLowerCase());
            if (pd != null) return pd;
        }
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            String cName = le.getCustomName();
            if (cName != null) {
                String clean = ChatColor.stripColor(cName).trim().toLowerCase();
                return petsByName.get(clean);
            }
        }
        return null;
    }

    public PetData getPetByName(String name) {
        if (name == null) return null;
        return petsByName.get(name.trim().toLowerCase());
    }

    public boolean unregisterPet(String name) {
        if (name == null) return false;
        PetData pd = petsByName.remove(name.trim().toLowerCase());
        if (pd != null) {
            if (pd.uuid != null && !pd.uuid.isEmpty()) {
                petsByUuid.remove(pd.uuid.toLowerCase());
            }
            save();
            return true;
        }
        return false;
    }

    public List<PetData> getAllPets() {
        return new ArrayList<PetData>(petsByName.values());
    }

    public LivingEntity respawnPet(PetData data) {
        if (data == null) return null;
        World w = Bukkit.getWorld(data.world);
        if (w == null) w = Bukkit.getWorlds().get(0);

        Location loc = new Location(w, data.x, data.y, data.z);
        EntityType type;
        try {
            type = EntityType.valueOf(data.entityType);
        } catch (Exception ex) {
            type = EntityType.SHEEP;
        }

        Entity e = w.spawnEntity(loc, type);
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            le.setCustomName(ChatColor.GREEN + "" + ChatColor.BOLD + data.name);
            le.setCustomNameVisible(true);
            le.setRemoveWhenFarAway(false);
            if (le instanceof Ageable) {
                ((Ageable) le).setAgeLock(true);
            }
            data.uuid = le.getUniqueId().toString();
            petsByUuid.put(data.uuid.toLowerCase(), data);
            save();
            return le;
        }
        return null;
    }
}
