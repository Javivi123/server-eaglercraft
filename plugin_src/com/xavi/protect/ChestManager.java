package com.xavi.protect;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class ChestManager {

    public static class ChestData {
        public String world;
        public int x;
        public int y;
        public int z;
        public String owner;
        public String claimedDate;

        public ChestData(String world, int x, int y, int z, String owner, String claimedDate) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
            this.claimedDate = claimedDate;
        }
    }

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, ChestData> chests = new LinkedHashMap<String, ChestData>();

    public ChestManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chests.yml");
        load();
    }

    public String toKey(String world, int x, int y, int z) {
        return (world != null ? world.toLowerCase() : "world") + "_" + x + "_" + y + "_" + z;
    }

    public String toKey(Block b) {
        if (b == null) return "";
        return toKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public Block getDoubleChestNeighbor(Block b) {
        if (b == null) return null;
        Material m = b.getType();
        if (m != Material.CHEST && m != Material.TRAPPED_CHEST) return null;

        try {
            if (b.getState() instanceof org.bukkit.block.Chest) {
                org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
                org.bukkit.inventory.Inventory inv = chest.getInventory();
                if (inv instanceof org.bukkit.inventory.DoubleChestInventory) {
                    org.bukkit.inventory.DoubleChestInventory dci = (org.bukkit.inventory.DoubleChestInventory) inv;
                    org.bukkit.block.DoubleChest dc = (org.bukkit.block.DoubleChest) dci.getHolder();
                    if (dc != null) {
                        org.bukkit.block.Chest left = (org.bukkit.block.Chest) dc.getLeftSide();
                        org.bukkit.block.Chest right = (org.bukkit.block.Chest) dc.getRightSide();
                        if (left != null && right != null) {
                            if (left.getBlock().getX() == b.getX() && left.getBlock().getY() == b.getY() && left.getBlock().getZ() == b.getZ()) {
                                return right.getBlock();
                            } else {
                                return left.getBlock();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        BlockFace[] faces = new BlockFace[]{
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        };
        for (BlockFace face : faces) {
            Block rel = b.getRelative(face);
            if (rel.getType() == m) {
                return rel;
            }
        }
        return null;
    }

    public synchronized void load() {
        chests.clear();
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (!file.exists()) {
                file.createNewFile();
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = cfg.getConfigurationSection("chests");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String world = sec.getString(key + ".world", "world");
                    int x = sec.getInt(key + ".x");
                    int y = sec.getInt(key + ".y");
                    int z = sec.getInt(key + ".z");
                    String owner = sec.getString(key + ".owner");
                    String date = sec.getString(key + ".claimedDate", "");
                    if (owner != null && !owner.trim().isEmpty()) {
                        chests.put(key.toLowerCase(), new ChestData(world, x, y, z, owner.trim(), date));
                    }
                }
            }
            plugin.getLogger().info("[ChestManager] Cargados " + chests.size() + " cofres reclamados desde chests.yml.");
        } catch (Exception e) {
            plugin.getLogger().warning("[ChestManager] Error cargando chests.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (cfg == null) cfg = new YamlConfiguration();
            cfg.set("chests", null);
            for (Map.Entry<String, ChestData> entry : chests.entrySet()) {
                String k = entry.getKey();
                ChestData d = entry.getValue();
                cfg.set("chests." + k + ".world", d.world);
                cfg.set("chests." + k + ".x", d.x);
                cfg.set("chests." + k + ".y", d.y);
                cfg.set("chests." + k + ".z", d.z);
                cfg.set("chests." + k + ".owner", d.owner);
                cfg.set("chests." + k + ".claimedDate", d.claimedDate);
            }
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[ChestManager] Error guardando chests.yml: " + e.getMessage());
        }
    }

    public synchronized String getChestOwner(Block b) {
        if (b == null) return null;
        Material m = b.getType();
        if (m != Material.CHEST && m != Material.TRAPPED_CHEST) return null;

        String key = toKey(b);
        ChestData data = chests.get(key);
        if (data != null && data.owner != null && !data.owner.trim().isEmpty()) {
            return data.owner.trim();
        }

        // Si es cofre doble, comprobar la otra mitad
        Block neighbor = getDoubleChestNeighbor(b);
        if (neighbor != null) {
            String nKey = toKey(neighbor);
            ChestData nData = chests.get(nKey);
            if (nData != null && nData.owner != null && !nData.owner.trim().isEmpty()) {
                chests.put(key, new ChestData(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), nData.owner.trim(), nData.claimedDate));
                save();
                return nData.owner.trim();
            }
        }
        return null;
    }

    public synchronized ChestData getChestData(Block b) {
        if (b == null) return null;
        String key = toKey(b);
        ChestData data = chests.get(key);
        if (data != null) return data;
        Block neighbor = getDoubleChestNeighbor(b);
        if (neighbor != null) {
            return chests.get(toKey(neighbor));
        }
        return null;
    }

    public synchronized boolean claimChest(Block b, String owner) {
        if (b == null || owner == null || owner.trim().isEmpty()) return false;
        Material m = b.getType();
        if (m != Material.CHEST && m != Material.TRAPPED_CHEST) return false;

        String cleanOwner = owner.trim();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());

        String key = toKey(b);
        chests.put(key, new ChestData(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), cleanOwner, dateStr));

        // Reclamar tambien la otra mitad si es un cofre doble
        Block neighbor = getDoubleChestNeighbor(b);
        if (neighbor != null) {
            String nKey = toKey(neighbor);
            chests.put(nKey, new ChestData(neighbor.getWorld().getName(), neighbor.getX(), neighbor.getY(), neighbor.getZ(), cleanOwner, dateStr));
        }

        save();
        return true;
    }

    public synchronized boolean removeChest(Block b) {
        if (b == null) return false;
        String key = toKey(b);
        boolean removed = chests.remove(key) != null;

        Block neighbor = getDoubleChestNeighbor(b);
        if (neighbor != null) {
            if (chests.remove(toKey(neighbor)) != null) {
                removed = true;
            }
        }

        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Map<String, ChestData> getAllChests() {
        return new LinkedHashMap<String, ChestData>(chests);
    }
}
