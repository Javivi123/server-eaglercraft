package com.xavi.protect;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Monster;
import org.bukkit.DyeColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.LeashHitch;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class XaviAdminProtect extends JavaPlugin implements Listener {

    private AllianceManager allianceManager;
    private HonorManager honorManager;
    private ChestManager chestManager;
    private NerfManager nerfManager;
    private PetManager petManager;
    private PoliceManager policeManager;

    public static class Zone {
        public String name;
        public String world;
        public int minX, maxX;
        public int minY, maxY;
        public int minZ, maxZ;
        public int x, y, z, radius;
        public boolean isBox;

        public Zone(String name, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.name = name;
            this.world = world;
            this.minX = Math.min(minX, maxX);
            this.maxX = Math.max(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.maxY = Math.max(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxZ = Math.max(minZ, maxZ);
            this.x = (this.minX + this.maxX) / 2;
            this.y = (this.minY + this.maxY) / 2;
            this.z = (this.minZ + this.maxZ) / 2;
            this.radius = Math.max((this.maxX - this.minX) / 2, (this.maxZ - this.minZ) / 2);
            this.isBox = true;
        }

        public Zone(String name, String world, int x, int y, int z, int radius) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.minX = x - radius;
            this.maxX = x + radius;
            this.minY = 0;
            this.maxY = 256;
            this.minZ = z - radius;
            this.maxZ = z + radius;
            this.isBox = false;
        }

        public boolean contains(Location loc) {
            if (loc == null || loc.getWorld() == null) return false;
            if (!loc.getWorld().getName().equalsIgnoreCase(this.world)) return false;
            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();
            if (isBox) {
                return bx >= minX && bx <= maxX && by >= minY && by <= maxY && bz >= minZ && bz <= maxZ;
            }
            int dx = Math.abs(bx - this.x);
            int dz = Math.abs(bz - this.z);
            return dx <= this.radius && dz <= this.radius;
        }
    }

    private final List<Zone> zones = new ArrayList<Zone>();
    private final List<Location> publicBlocks = new ArrayList<Location>();
    private final Set<String> trustedFriends = new HashSet<String>();
    private final Set<String> frozenPlayers = new HashSet<String>();
    private final Set<String> godPlayers = new HashSet<String>();

    private File warningsFile;
    private FileConfiguration warningsCfg;
    private final Map<String, List<String>> playerWarnings = new LinkedHashMap<String, List<String>>();

    private int idleLimitMinutes = 10;
    private int idleSeconds = 0;

    private String ownerName = "Xavi";
    private boolean houseProtectionEnabled = true;
    private boolean autoBanOnGrief = true;
    private boolean protectHouseChests = true;
    private boolean antiExplosionDestroy = true;
    private boolean antiFireSpread = true;
    private boolean globalBuildLock = false;
    private boolean keepInventoryEnabled = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadWarnings();

        allianceManager = new AllianceManager(this);
        honorManager = new HonorManager(this);
        chestManager = new ChestManager(this);
        nerfManager = new NerfManager(this);
        petManager = new PetManager(this);
        policeManager = new PoliceManager(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        // KeepInventory segun configuracion en todos los mundos
        for (World w : Bukkit.getWorlds()) {
            w.setGameRuleValue("keepInventory", keepInventoryEnabled ? "true" : "false");
        }

        // Auto-save every 2 minutes (2400 ticks)
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                saveAllWorldsSilent();
            }
        }, 2400L, 2400L);

        // Auto-shutdown on idle (10 minutes with 0 players)
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                if (idleLimitMinutes <= 0) return;
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    idleSeconds += 5;
                    if (idleSeconds >= idleLimitMinutes * 60) {
                        getLogger().info("==================================================");
                        getLogger().info("[AUTO-APAGADO] Servidor sin jugadores durante " + idleLimitMinutes + " minutos.");
                        getLogger().info("[AUTO-APAGADO] Guardando mundo y apagando servidor para liberar PC...");
                        getLogger().info("==================================================");
                        saveAllWorldsSilent();
                        Bukkit.shutdown();
                    }
                } else {
                    idleSeconds = 0;
                }
            }
        }, 100L, 100L);

        getLogger().info("==================================================");
        getLogger().info(" XaviAdminProtect ACTIVADO - Casa de Xavi Protegida");
        getLogger().info(" Auto-Ban en Casa: " + (autoBanOnGrief ? "SI" : "NO"));
        getLogger().info(" Sistema Advertencias /warn (Ban a 4): ACTIVADO");
        getLogger().info(" Auto-Apagado por Inactividad (" + idleLimitMinutes + " min vacio): ACTIVADO");
        getLogger().info(" Sistema de Alianzas y Guerras: ACTIVADO");
        getLogger().info(" Sistema de Honor Estilo RDR2: ACTIVADO");
        getLogger().info(" Auto-Guardado cada 2 min y al apagar: ACTIVADO");
        getLogger().info("==================================================");
    }

    @Override
    public void onDisable() {
        getLogger().info("[XaviAdminProtect] Guardando jugadores y mundo antes de apagar...");
        saveWarnings();
        if (allianceManager != null) allianceManager.save();
        if (honorManager != null) honorManager.save();
        if (chestManager != null) chestManager.save();
        if (nerfManager != null) nerfManager.save();
        if (petManager != null) petManager.save();
        if (policeManager != null) policeManager.removePolice();
        saveAllWorldsSilent();
        getLogger().info("[XaviAdminProtect] Mundo, alianzas, honor, cofres, nerfeos y rangos guardados correctamente.");
    }

    public AllianceManager getAllianceManager() { return allianceManager; }
    public HonorManager getHonorManager() { return honorManager; }
    public ChestManager getChestManager() { return chestManager; }
    public NerfManager getNerfManager() { return nerfManager; }
    public PetManager getPetManager() { return petManager; }
    public PoliceManager getPoliceManager() { return policeManager; }
    public String getOwnerName() { return ownerName; }
    public Map<String, List<String>> getPlayerWarnings() { return playerWarnings; }

        public void addWarning(String target, String reason, String issuer) {
        if (target.equalsIgnoreCase(ownerName)) return;
        String key = target.toLowerCase();
        List<String> list = playerWarnings.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            playerWarnings.put(key, list);
        }
        String timeStr = new SimpleDateFormat("HH:mm").format(new Date());
        list.add("[" + timeStr + "] (" + issuer + ") " + reason);
        saveWarnings();
    }

    private void loadWarnings() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            warningsFile = new File(getDataFolder(), "warnings.yml");
            if (!warningsFile.exists()) {
                warningsFile.createNewFile();
            }
            warningsCfg = YamlConfiguration.loadConfiguration(warningsFile);
            playerWarnings.clear();
            ConfigurationSection sec = warningsCfg.getConfigurationSection("warnings");
            if (sec != null) {
                for (String p : sec.getKeys(false)) {
                    List<String> list = sec.getStringList(p);
                    if (list != null) {
                        playerWarnings.put(p.toLowerCase(), new ArrayList<String>(list));
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("[XaviAdminProtect] Error cargando warnings.yml: " + e.getMessage());
        }
    }

    public void saveWarnings() {
        try {
            if (warningsFile == null) warningsFile = new File(getDataFolder(), "warnings.yml");
            if (warningsCfg == null) warningsCfg = new YamlConfiguration();
            warningsCfg.set("warnings", null);
            for (Map.Entry<String, List<String>> entry : playerWarnings.entrySet()) {
                warningsCfg.set("warnings." + entry.getKey(), entry.getValue());
            }
            warningsCfg.save(warningsFile);
        } catch (Exception e) {
            getLogger().warning("[XaviAdminProtect] Error guardando warnings.yml: " + e.getMessage());
        }
    }

    public void saveAllWorldsSilent() {
        try {
            if (allianceManager != null) allianceManager.save();
            if (honorManager != null) honorManager.save();
            if (chestManager != null) chestManager.save();
            if (nerfManager != null) nerfManager.save();
            Bukkit.savePlayers();
            for (World w : Bukkit.getWorlds()) {
                w.save();
            }
        } catch (Exception e) {
            getLogger().warning("Error al autoguardar: " + e.getMessage());
        }
    }

    private void loadSettings() {
        FileConfiguration cfg = getConfig();
        ownerName = cfg.getString("owner", "Xavi");
        idleLimitMinutes = cfg.getInt("auto-shutdown-idle-minutes", 10);
        houseProtectionEnabled = cfg.getBoolean("house-protection-enabled", true);
        autoBanOnGrief = cfg.getBoolean("auto-ban-on-grief", true);
        protectHouseChests = cfg.getBoolean("protect-house-chests", true);
        antiExplosionDestroy = cfg.getBoolean("anti-explosion-destroy", true);
        antiFireSpread = cfg.getBoolean("anti-fire-spread", true);
        globalBuildLock = cfg.getBoolean("global-build-lock", false);
        keepInventoryEnabled = cfg.getBoolean("keep-inventory", true);

        for (World w : Bukkit.getWorlds()) {
            w.setGameRuleValue("keepInventory", keepInventoryEnabled ? "true" : "false");
        }

        trustedFriends.clear();
        List<String> friends = cfg.getStringList("trusted-friends");
        if (friends != null) {
            for (String f : friends) {
                trustedFriends.add(f.toLowerCase());
            }
        }

        zones.clear();
        ConfigurationSection zSec = cfg.getConfigurationSection("zones");
        if (zSec != null) {
            for (String key : zSec.getKeys(false)) {
                String w = zSec.getString(key + ".world", "world");
                if (zSec.contains(key + ".minX")) {
                    int minX = zSec.getInt(key + ".minX");
                    int maxX = zSec.getInt(key + ".maxX");
                    int minY = zSec.getInt(key + ".minY", 0);
                    int maxY = zSec.getInt(key + ".maxY", 256);
                    int minZ = zSec.getInt(key + ".minZ");
                    int maxZ = zSec.getInt(key + ".maxZ");
                    zones.add(new Zone(key, w, minX, maxX, minY, maxY, minZ, maxZ));
                } else {
                    int x = zSec.getInt(key + ".x", -276);
                    int y = zSec.getInt(key + ".y", 67);
                    int z = zSec.getInt(key + ".z", -10);
                    int r = zSec.getInt(key + ".radius", 30);
                    zones.add(new Zone(key, w, x, y, z, r));
                }
            }
        }

        if (zones.isEmpty()) {
            // Default: Xavi's Wooden House near (-276, 67, -10) with radius 12 (covers just the house, not the forest)
            zones.add(new Zone("CasaPrincipal", "world", -276, 67, -10, 12));
            saveSettings();
        }

        publicBlocks.clear();
        // Top furnace in Xavi's house (-271, 79, -6) is ALWAYS public for everyone
        publicBlocks.add(new Location(Bukkit.getWorld("world"), -271, 79, -6));
        ConfigurationSection pSec = cfg.getConfigurationSection("public-blocks");
        if (pSec != null) {
            for (String key : pSec.getKeys(false)) {
                String w = pSec.getString(key + ".world", "world");
                int x = pSec.getInt(key + ".x");
                int y = pSec.getInt(key + ".y");
                int z = pSec.getInt(key + ".z");
                World worldObj = Bukkit.getWorld(w);
                if (worldObj != null) {
                    publicBlocks.add(new Location(worldObj, x, y, z));
                }
            }
        }
    }

    private void saveSettings() {
        FileConfiguration cfg = getConfig();
        cfg.set("owner", ownerName);
        cfg.set("auto-shutdown-idle-minutes", idleLimitMinutes);
        cfg.set("house-protection-enabled", houseProtectionEnabled);
        cfg.set("auto-ban-on-grief", autoBanOnGrief);
        cfg.set("protect-house-chests", protectHouseChests);
        cfg.set("anti-explosion-destroy", antiExplosionDestroy);
        cfg.set("anti-fire-spread", antiFireSpread);
        cfg.set("global-build-lock", globalBuildLock);
        cfg.set("keep-inventory", keepInventoryEnabled);
        cfg.set("trusted-friends", new ArrayList<String>(trustedFriends));

        cfg.set("zones", null);
        for (Zone z : zones) {
            String path = "zones." + z.name;
            cfg.set(path + ".world", z.world);
            if (z.isBox) {
                cfg.set(path + ".minX", z.minX);
                cfg.set(path + ".maxX", z.maxX);
                cfg.set(path + ".minY", z.minY);
                cfg.set(path + ".maxY", z.maxY);
                cfg.set(path + ".minZ", z.minZ);
                cfg.set(path + ".maxZ", z.maxZ);
            } else {
                cfg.set(path + ".x", z.x);
                cfg.set(path + ".y", z.y);
                cfg.set(path + ".z", z.z);
                cfg.set(path + ".radius", z.radius);
            }
        }
        saveConfig();
    }

    private boolean isOwner(CommandSender sender) {
        if (!(sender instanceof Player)) return true; // Console/RCON is always owner
        Player p = (Player) sender;
        return p.getName().equalsIgnoreCase(ownerName) || p.isOp();
    }

    private boolean isTrusted(Player p) {
        if (p == null) return false;
        if (p.getName().equalsIgnoreCase(ownerName)) return true;
        return trustedFriends.contains(p.getName().toLowerCase());
    }

        public LivingEntity getTargetAnimal(Player p, int maxDistance) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize().multiply(0.35);
        Location cur = eye.clone();
        int steps = (int) (maxDistance / 0.35);

        for (int i = 0; i < steps; i++) {
            cur.add(dir);
            Block b = cur.getBlock();
            if (b != null && b.getType().isSolid() && b.getType() != Material.GLASS && b.getType() != Material.THIN_GLASS) {
                return null;
            }
            for (Entity e : cur.getWorld().getNearbyEntities(cur, 0.75, 0.75, 0.75)) {
                if (e instanceof LivingEntity && !e.equals(p) && !(e instanceof Player)) {
                    if (e instanceof org.bukkit.entity.Animals || e instanceof org.bukkit.entity.Tameable || e instanceof org.bukkit.entity.IronGolem || e instanceof org.bukkit.entity.Villager) {
                        return (LivingEntity) e;
                    }
                }
            }
        }
        return null;
    }

    public Block getTargetChestBlock(Player p, int maxDistance) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize().multiply(0.4);
        Location cur = eye.clone();
        for (int i = 0; i < maxDistance * 2.5; i++) {
            cur.add(dir);
            Block b = cur.getBlock();
            if (b != null) {
                Material m = b.getType();
                if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
                    return b;
                }
                if (m != Material.AIR && m != Material.WATER && m != Material.STATIONARY_WATER
                        && m != Material.LAVA && m != Material.STATIONARY_LAVA
                        && m != Material.LONG_GRASS && m != Material.TORCH) {
                    return b;
                }
            }
        }
        return null;
    }

    private Zone getZoneAt(Location loc) {
        if (!houseProtectionEnabled) return null;
        for (Zone z : zones) {
            if (z.contains(loc)) return z;
        }
        return null;
    }

    private void punishHouseGriefer(final Player p, String actionDesc, Zone zone) {
        if (honorManager != null) {
            honorManager.addHonor(p, -15, "Intentar " + actionDesc + " en la casa de Xavi");
        }
        if (autoBanOnGrief) {
            // Expulsión (Kick) automática SIN baneo permanente para permitir reconexión inmediata
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[AUTO-KICK] "
                    + ChatColor.YELLOW + "El jugador " + ChatColor.RED + p.getName()
                    + ChatColor.YELLOW + " ha sido EXPULSADO por intentar " + actionDesc + " en la casa de Xavi. (La casa sigue intacta)");
            getLogger().info("[AUTO-KICK] Jugador " + p.getName() + " expulsado por intentar " + actionDesc + " en " + zone.name);
            Bukkit.getScheduler().runTask(this, new Runnable() {
                @Override
                public void run() {
                    p.kickPlayer(ChatColor.GOLD + "" + ChatColor.BOLD + "¡EXPULSADO POR SEGURIDAD!\n\n"
                            + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + "Intentaste " + actionDesc + " en la casa protegida de Xavi.\n"
                            + ChatColor.GREEN + "✔ Tu acción fue cancelada y la casa está intacta.\n\n"
                            + ChatColor.AQUA + "Puedes volver a entrar al servidor inmediatamente. ¡Ten más cuidado!");
                }
            });
        } else {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "[PROTECCION] "
                    + ChatColor.YELLOW + "¡No puedes " + actionDesc + " en la casa protegida de Xavi!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player p = event.getPlayer();
        if (p.getName().equalsIgnoreCase(ownerName)) {
            String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : "";
            if (!ip.equals("127.0.0.1") && !ip.equals("0:0:0:0:0:0:0:1") && !ip.equals("::1") && !ip.equals("192.168.137.1")) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                        ChatColor.RED + "" + ChatColor.BOLD + "¡ACCESO DENEGADO!\n"
                        + ChatColor.YELLOW + "No puedes entrar con el nombre del administrador (Xavi) desde otro ordenador.");
                getLogger().warning("[Seguridad] Intento bloqueado de entrar como Xavi desde IP: " + ip);
            }
        }
    }

    public void restoreEpkInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(1, new ItemStack(Material.EGG, 1, (short) 0));
        inv.setItem(2, new ItemStack(Material.TORCH, 40, (short) 0));
        inv.setItem(3, new ItemStack(Material.IRON_SPADE, 1, (short) 220));
        inv.setItem(4, new ItemStack(Material.IRON_PICKAXE, 1, (short) 248));
        inv.setItem(5, new ItemStack(Material.STONE_SWORD, 1, (short) 106));
        inv.setItem(6, new ItemStack(Material.MAP, 1, (short) 0));
        inv.setItem(7, new ItemStack(Material.IRON_SWORD, 1, (short) 10));
        inv.setItem(8, new ItemStack(Material.COBBLESTONE, 64, (short) 0));
        inv.setItem(10, new ItemStack(Material.ROTTEN_FLESH, 3, (short) 0));
        inv.setItem(11, new ItemStack(Material.COBBLESTONE, 15, (short) 0));
        inv.setItem(12, new ItemStack(Material.DIRT, 15, (short) 0));
        inv.setItem(13, new ItemStack(Material.IRON_PICKAXE, 1, (short) 0));
        inv.setItem(17, new ItemStack(Material.STONE, 15, (short) 1));
        inv.setItem(27, new ItemStack(Material.STONE, 5, (short) 5));
        inv.setItem(28, new ItemStack(Material.MILK_BUCKET, 1, (short) 0));
        inv.setItem(29, new ItemStack(Material.MILK_BUCKET, 1, (short) 0));
        inv.setItem(30, new ItemStack(Material.BUCKET, 1, (short) 0));
        inv.setItem(33, new ItemStack(Material.GRAVEL, 9, (short) 0));
        inv.setItem(34, new ItemStack(Material.STONE, 15, (short) 3));
        p.setLevel(10);
        p.setExp(0.704f);
        p.updateInventory();
        p.saveData();
    }

    private boolean isInventoryEmpty(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    public void sendOnlinePlayersList(CommandSender sender) {
        if (sender == null) return;
        Player p = (sender instanceof Player) ? (Player) sender : null;

        List<String> playerList = new ArrayList<String>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            String alTag = "";
            if (allianceManager != null) {
                AllianceManager.Alliance al = allianceManager.getAlliance(online.getName());
                if (al != null) {
                    alTag = ChatColor.AQUA + "[" + al.name + "] ";
                }
            }

            String nameFormatted;
            if (online.getName().equalsIgnoreCase(ownerName)) {
                nameFormatted = ChatColor.GOLD + "" + ChatColor.BOLD + "★ " + online.getName() + ChatColor.RESET;
            } else if (p != null && online.getName().equalsIgnoreCase(p.getName())) {
                nameFormatted = ChatColor.GREEN + online.getName() + ChatColor.GRAY + " (tú)" + ChatColor.RESET;
            } else {
                nameFormatted = ChatColor.YELLOW + online.getName() + ChatColor.RESET;
            }
            playerList.add(alTag + nameFormatted);
        }

        int total = Bukkit.getOnlinePlayers().size();
        sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "--------------------------------------------------");
        if (total <= 1) {
            sender.sendMessage(ChatColor.GOLD + "👥 " + ChatColor.YELLOW + ChatColor.BOLD + "Jugadores conectados ahora mismo (" + ChatColor.WHITE + "1" + ChatColor.YELLOW + ChatColor.BOLD + "):");
            sender.sendMessage(ChatColor.GRAY + "  » Solo estás tú en línea " + ChatColor.DARK_GRAY + "(" + ChatColor.RED + "no hay nadie más conectado" + ChatColor.DARK_GRAY + ").");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < playerList.size(); i++) {
                if (i > 0) sb.append(ChatColor.GRAY + ", ");
                sb.append(playerList.get(i));
            }
            sender.sendMessage(ChatColor.GOLD + "👥 " + ChatColor.YELLOW + ChatColor.BOLD + "Jugadores conectados ahora mismo (" + ChatColor.GREEN + total + ChatColor.YELLOW + ChatColor.BOLD + "):");
            sender.sendMessage(ChatColor.WHITE + "  » " + sb.toString());
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "--------------------------------------------------");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        idleSeconds = 0;
        final Player p = event.getPlayer();
        if (p.getName().equalsIgnoreCase(ownerName)) {
            if (!p.isOp()) {
                p.setOp(true);
            }
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                @Override
                public void run() {
                    if (p.isOnline() && isInventoryEmpty(p)) {
                        restoreEpkInventory(p);
                        p.sendMessage(ChatColor.GREEN + "[XaviAdminProtect] ¡Tu inventario de New World (8).epk ha sido cargado y protegido!");
                    }
                }
            }, 10L);
        } else {
            if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
                Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                    @Override
                    public void run() {
                        if (p.isOnline()) {
                            nerfManager.applyNerf(p);
                            nerfManager.sendNerfMessage(p);
                        }
                    }
                }, 10L);
            }
            final List<String> wList = playerWarnings.get(p.getName().toLowerCase());
            if (wList != null && !wList.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                    @Override
                    public void run() {
                        if (p.isOnline()) {
                            p.sendMessage(ChatColor.GOLD + "[Servidor] Tienes " + ChatColor.RED + wList.size() + "/4 advertencias" + ChatColor.GOLD + " acumuladas. A las 4 serás baneado.");
                        }
                    }
                }, 20L);
            }
        }

        // Notificar siempre de jugadores en línea al entrar al servidor
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (p.isOnline()) {
                    sendOnlinePlayersList(p);
                }
            }
        }, 15L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (Bukkit.getOnlinePlayers().size() <= 1) {
            idleSeconds = 0;
        }
        saveAllWorldsSilent();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getName().toLowerCase())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                event.setTo(from);
                p.sendMessage(ChatColor.AQUA + "¡Estas CONGELADO por Xavi y no puedes moverte!");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.startsWith("/")) return;

        String withoutSlash = raw.substring(1).trim();
        if (withoutSlash.isEmpty()) return;

        String[] parts = withoutSlash.split("\\s+");
        String cmd = parts[0].toLowerCase();

        // Soporte universal para /tp, /teleport, /minecraft:tp
        if (cmd.equals("tp") || cmd.equals("teleport") || cmd.equals("minecraft:tp")) {
            Player sender = event.getPlayer();

            // Si solo escribio /tp sin argumentos
            if (parts.length == 1) {
                sender.sendMessage(ChatColor.GOLD + "[Teletransporte] " + ChatColor.YELLOW + "Uso: /tp <jugador>");
                event.setCancelled(true);
                return;
            }

            // Si escribio /tp <objetivo>
            if (parts.length == 2) {
                String targetName = parts[1];
                Player target = Bukkit.getPlayer(targetName);

                if (target == null || !target.isOnline()) {
                    if (!sender.isOp()) {
                        sender.sendMessage(ChatColor.RED + "El jugador '" + targetName + "' no está conectado o no existe.");
                        event.setCancelled(true);
                        return;
                    }
                    // Si el remitente es OP, permitimos que CraftBukkit vanilla intente resolver (ej. selectores o coords)
                    return;
                }

                if (target.equals(sender)) {
                    sender.sendMessage(ChatColor.YELLOW + "Ya estás en tu propia ubicación.");
                    event.setCancelled(true);
                    return;
                }

                if (frozenPlayers.contains(sender.getName().toLowerCase())) {
                    sender.sendMessage(ChatColor.RED + "¡No puedes teletransportarte porque estás congelado!");
                    event.setCancelled(true);
                    return;
                }

                // Teletransporte directo para TODOS los jugadores
                sender.teleport(target.getLocation());
                sender.sendMessage(ChatColor.GREEN + "¡Te has teletransportado hacia " + ChatColor.GOLD + target.getName() + ChatColor.GREEN + "!");
                target.sendMessage(ChatColor.AQUA + sender.getName() + ChatColor.GRAY + " se ha teletransportado hacia ti con /tp.");
                try {
                    sender.playSound(sender.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    target.playSound(target.getLocation(), Sound.ENDERMAN_TELEPORT, 0.7f, 1.2f);
                } catch (Exception ignored) {}

                event.setCancelled(true);
                return;
            }

            // Si escribio mas de un argumento (/tp jugador1 jugador2 o /tp x y z)
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "Solo los administradores pueden teletransportar a otros jugadores o a coordenadas. Usa: /tp <jugador>");
                event.setCancelled(true);
                return;
            }
            // Si es OP, dejamos que el comando vanilla ejecute libremente
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (policeManager != null && policeManager.isPolice(event.getEntity())) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FIRE || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || event.getCause() == EntityDamageEvent.DamageCause.LAVA) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (godPlayers.contains(p.getName().toLowerCase())) {
                event.setCancelled(true);
                return;
            }
        }
        if (isJosefa(event.getEntity())) {
            // Josefa es inmune a peligros ambientales (fuego, lava, caida, asfixia, cactus, explosiones)
            if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Interceptación de ataque al policía (de frente o por la espalda)
        if (policeManager != null && policeManager.isPolice(event.getEntity())) {
            Player copAttacker = null;
            if (event.getDamager() instanceof Player) {
                copAttacker = (Player) event.getDamager();
            } else if (event.getDamager() instanceof Projectile) {
                Projectile proj = (Projectile) event.getDamager();
                if (proj.getShooter() instanceof Player) {
                    copAttacker = (Player) proj.getShooter();
                }
            }
            if (copAttacker != null) {
                policeManager.handleAttackOnPolice(copAttacker);
                return;
            }
        }

        if (isJosefa(event.getEntity())) {
            Player attacker = null;
            if (event.getDamager() instanceof Player) {
                attacker = (Player) event.getDamager();
            } else if (event.getDamager() instanceof Projectile) {
                Projectile proj = (Projectile) event.getDamager();
                if (proj.getShooter() instanceof Player) {
                    attacker = (Player) proj.getShooter();
                }
            }

            if (attacker != null) {
                PetManager.PetData pd = (petManager != null ? petManager.getPetData(event.getEntity()) : null);
                String petOwner = (pd != null ? pd.owner : ownerName);
                String petName = (pd != null ? pd.name : "Josefa");

                if (attacker.getName().equalsIgnoreCase(petOwner) || attacker.getName().equalsIgnoreCase(ownerName)) {
                    // El dueño o Xavi pueden interactuar/alimentar a su mascota
                    return;
                }
                event.setCancelled(true);
                banPlayer(attacker.getName(), "Atacar o intentar matar a la mascota protegida de " + petOwner + " (" + petName + ")");
                if (policeManager != null) {
                    policeManager.witnessCrime(attacker, "Ataque a Mascota", "intentar matar a " + petName, event.getEntity().getLocation());
                }
                return;
            } else {
                event.setCancelled(true);
                return;
            }
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) event.getDamager();
            if (proj.getShooter() instanceof Player) {
                attacker = (Player) proj.getShooter();
            }
        }

        if (attacker != null) {
            if (nerfManager != null && nerfManager.isNerfed(attacker.getName())) {
                event.setDamage(event.getDamage() * 0.60);
            }
        }

        if (attacker != null && event.getEntity() instanceof Player) {
            if (policeManager != null) {
                policeManager.witnessCrime(attacker, "Agresión Física", "atacar a un ciudadano en la vía pública", event.getEntity().getLocation());
            }
            Player victim = (Player) event.getEntity();
            if (frozenPlayers.contains(attacker.getName().toLowerCase())) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.AQUA + "¡Estás congelado y no puedes atacar!");
                return;
            }
            if (godPlayers.contains(victim.getName().toLowerCase())) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.GOLD + victim.getName() + " tiene el modo Dios activado.");
                return;
            }

            // Alliance Friendly Fire check
            if (allianceManager != null && allianceManager.areAllies(attacker.getName(), victim.getName())) {
                AllianceManager.Alliance al = allianceManager.getAlliance(attacker.getName());
                if (al != null && !al.friendlyFire) {
                    event.setCancelled(true);
                    attacker.sendMessage(ChatColor.RED + "¡El fuego amigo está DESACTIVADO en tu alianza [" + al.name + "]!");
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.getName().equalsIgnoreCase(victim.getName())) {

            // Alliance stats & War announcements
            if (allianceManager != null) {
                AllianceManager.Alliance alKiller = allianceManager.getAlliance(killer.getName());
                AllianceManager.Alliance alVictim = allianceManager.getAlliance(victim.getName());
                if (alKiller != null) {
                    alKiller.kills++;
                    if (alVictim != null && allianceManager.areAtWar(killer.getName(), victim.getName())) {
                        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "⚔️ [GUERRA DE ALIANZAS] "
                                + ChatColor.YELLOW + killer.getName() + " [" + alKiller.name + "]"
                                + ChatColor.GRAY + " ha liquidado en combate a "
                                + ChatColor.YELLOW + victim.getName() + " [" + alVictim.name + "]!");
                    }
                    allianceManager.save();
                }
            }

            // RDR2 Honor adjustment
            if (honorManager != null) {
                if (honorManager.isOutlaw(victim.getName())) {
                    honorManager.addHonor(killer, 15, "Cazarrecompensas: abatir al forajido " + victim.getName());
                    Bukkit.broadcastMessage(ChatColor.GOLD + "🤠 [CAZARRECOMPENSAS] "
                            + ChatColor.YELLOW + killer.getName() + ChatColor.GREEN + " ha cobrado la recompensa por eliminar al forajido buscado "
                            + ChatColor.RED + victim.getName() + ChatColor.GREEN + " (+15 Honor)!");
                } else if (honorManager.getHonor(victim.getName()) >= 0) {
                    honorManager.addHonor(killer, -25, "Asesinato a sangre fría de un inocente (" + victim.getName() + ")");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (policeManager != null && policeManager.isPolice(event.getEntity())) {
            policeManager.handlePoliceDeath();
            return;
        }

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            Player killer = victim.getKiller();
            if (killer != null && !killer.equals(victim) && policeManager != null) {
                policeManager.witnessCrime(killer, "Homicidio en Primer Grado", "asesinar al ciudadano " + victim.getName(), victim.getLocation());
            }
        }

        if (isJosefa(event.getEntity())) {
            PetManager.PetData pd = (petManager != null ? petManager.getPetData(event.getEntity()) : null);
            final PetManager.PetData finalPd = pd;
            String petOwner = (pd != null ? pd.owner : ownerName);
            String petName = (pd != null ? pd.name : "Josefa");

            Player killer = event.getEntity().getKiller();
            if (killer != null && !killer.getName().equalsIgnoreCase(ownerName) && !killer.getName().equalsIgnoreCase(petOwner)) {
                banPlayer(killer.getName(), "Matar a la mascota protegida de " + petOwner + " (" + petName + ")");
            }
            final World w = event.getEntity().getWorld();
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                @Override
                public void run() {
                    if (finalPd != null && petManager != null) {
                        petManager.respawnPet(finalPd);
                    } else {
                        Zone house = zones.isEmpty() ? null : zones.get(0);
                        setupJosefa(w, house, null, null);
                    }
                }
            }, 30L);
        }

        if (event.getEntity() instanceof Monster) {
            Player killer = event.getEntity().getKiller();
            if (killer != null && honorManager != null) {
                // Defending the realm from hostile monsters
                honorManager.addHonor(killer, 2, "Proteger el reino eliminando monstruos hostiles");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        final Player p = event.getPlayer();
        if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
            Bukkit.getScheduler().runTaskLater(this, new Runnable() {
                @Override
                public void run() {
                    if (p.isOnline()) {
                        nerfManager.applyNerf(p);
                    }
                }
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player p = event.getPlayer();
        if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
            int reduced = Math.max(1, event.getAmount() / 2);
            event.setAmount(reduced);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (nerfManager != null && nerfManager.isLockedItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player p = event.getPlayer();
        if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
            PlayerInventory inv = p.getInventory();
            boolean hasSpace = false;
            for (int i = 0; i < 18; i++) {
                ItemStack item = inv.getItem(i);
                if (item == null || item.getType() == Material.AIR) {
                    hasSpace = true;
                    break;
                }
                if (item.isSimilar(event.getItem().getItemStack()) && item.getAmount() < item.getMaxStackSize()) {
                    hasSpace = true;
                    break;
                }
            }
            if (!hasSpace) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String alPrefix = "";
        if (allianceManager != null) {
            AllianceManager.Alliance al = allianceManager.getAlliance(p.getName());
            if (al != null) {
                alPrefix = ChatColor.AQUA + "[" + al.name + "] ";
            }
        }

        String honorPrefix = "";
        if (honorManager != null) {
            int honor = honorManager.getHonor(p.getName());
            honorPrefix = honorManager.getHonorBadge(honor) + " ";
        }

        String nameColor = p.getName().equalsIgnoreCase(ownerName)
                ? (ChatColor.GOLD + "" + ChatColor.BOLD)
                : ("" + ChatColor.WHITE);

        event.setFormat(alPrefix + honorPrefix + nameColor + "%1$s" + ChatColor.GRAY + ": " + ChatColor.WHITE + "%2$s");
    }

    private boolean isHouseStructureBlock(Material m) {
        if (m == null) return false;
        if (m == Material.DIRT || m == Material.GRASS || m == Material.SAND || m == Material.GRAVEL
                || m == Material.LOG || m == Material.LOG_2 || m == Material.LEAVES || m == Material.LEAVES_2
                || m == Material.LONG_GRASS || m == Material.DOUBLE_PLANT || m == Material.YELLOW_FLOWER
                || m == Material.RED_ROSE || m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.VINE) {
            return false;
        }
        return true;
    }

    public boolean isPublicBlock(Block b) {
        if (b == null) return false;
        // Top furnace in Xavi's house at (-271, 79, -6) is free for everyone to use!
        if (b.getX() == -271 && b.getY() == 79 && b.getZ() == -6) {
            return true;
        }
        for (Location loc : publicBlocks) {
            if (loc.getBlockX() == b.getX() && loc.getBlockY() == b.getY() && loc.getBlockZ() == b.getZ()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getName().toLowerCase())) {
            event.setCancelled(true);
            return;
        }

        Block b = event.getBlock();
        Material m = b.getType();

        // Proteccion de rotura de cofres privados
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
            if (chestManager != null) {
                String owner = chestManager.getChestOwner(b);
                if (owner != null) {
                    boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                    boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                    if (!isChestOwner && !isMaster) {
                        event.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Cofres] "
                                + ChatColor.RED + "¡No puedes romper el cofre de " + ChatColor.GOLD + owner + ChatColor.RED + "!");
                        return;
                    } else {
                        chestManager.removeChest(b);
                        p.sendMessage(ChatColor.YELLOW + "[Cofres] Has roto un cofre protegido. Ha quedado liberado.");
                    }
                }
            }
        } else {
            // Comprobar si el bloque de arriba es un cofre protegido
            Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
            if (above.getType() == Material.CHEST || above.getType() == Material.TRAPPED_CHEST) {
                if (chestManager != null) {
                    String owner = chestManager.getChestOwner(above);
                    if (owner != null) {
                        boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                        boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                        if (!isChestOwner && !isMaster) {
                            event.setCancelled(true);
                            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Cofres] "
                                    + ChatColor.RED + "¡No puedes romper el bloque debajo del cofre de " + ChatColor.GOLD + owner + ChatColor.RED + "!");
                            return;
                        }
                    }
                }
            }
        }

        Zone z = getZoneAt(event.getBlock().getLocation());
        if (z != null && !isTrusted(p)) {
            event.setCancelled(true);
            if (isPublicBlock(event.getBlock())) {
                p.sendMessage(ChatColor.YELLOW + "[Casa de Xavi] ¡Este horno es público para que todos lo usen, no lo rompas!");
                return;
            }
            if (isHouseStructureBlock(m)) {
                punishHouseGriefer(p, "romper la casa de Xavi", z);
            } else {
                p.sendMessage(ChatColor.YELLOW + "[Casa de Xavi] ¡No puedes romper árboles ni plantas al lado de la casa de Xavi!");
            }
            return;
        }
        if (globalBuildLock && !isTrusted(p)) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "[Servidor] Xavi ha bloqueado la destruccion de bloques en el mundo.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getName().toLowerCase())) {
            event.setCancelled(true);
            return;
        }
        Block b = event.getBlockPlaced();

        // Proteccion contra cofres dobles no autorizados
        if (b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) {
            if (chestManager != null) {
                Block neighbor = chestManager.getDoubleChestNeighbor(b);
                if (neighbor != null) {
                    String nOwner = chestManager.getChestOwner(neighbor);
                    if (nOwner != null) {
                        boolean isNeighborOwner = nOwner.equalsIgnoreCase(p.getName());
                        boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                        if (!isNeighborOwner && !isMaster) {
                            event.setCancelled(true);
                            p.sendMessage(ChatColor.RED + "[Cofres] ¡No puedes colocar un cofre junto al cofre de " + ChatColor.GOLD + nOwner + ChatColor.RED + "!");
                            return;
                        } else {
                            chestManager.claimChest(b, nOwner);
                        }
                    }
                }
            }
        }

        // Proteccion contra robo mediante tolvas
        if (b.getType() == Material.HOPPER) {
            if (chestManager != null) {
                Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
                if (above.getType() == Material.CHEST || above.getType() == Material.TRAPPED_CHEST) {
                    String cOwner = chestManager.getChestOwner(above);
                    if (cOwner != null && !cOwner.equalsIgnoreCase(p.getName()) && !p.getName().equalsIgnoreCase(ownerName)) {
                        event.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "[Cofres] ¡No puedes colocar tolvas bajo el cofre de " + ChatColor.GOLD + cOwner + ChatColor.RED + "!");
                        return;
                    }
                }
            }
        }

        Zone z = getZoneAt(b.getLocation());
        if (z != null && !isTrusted(p)) {
            event.setCancelled(true);
            Material m = b.getType();
            if (m == Material.TNT || m == Material.FIRE || m == Material.LAVA || m == Material.STATIONARY_LAVA) {
                punishHouseGriefer(p, "poner TNT/Fuego/Lava", z);
            } else {
                punishHouseGriefer(p, "modificar o construir", z);
            }
            return;
        }
        if (globalBuildLock && !isTrusted(p)) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "[Servidor] Xavi ha bloqueado la construccion en el mundo.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player p = event.getPlayer();
        Block b = event.getBlockClicked().getRelative(event.getBlockFace());
        Zone z = getZoneAt(b.getLocation());
        if (z != null && !isTrusted(p)) {
            event.setCancelled(true);
            punishHouseGriefer(p, "vaciar cubos de lava/agua", z);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block b = event.getClickedBlock();
            Material type = b.getType();

            // Bloqueo y Reclamo de Cofres Privados Global
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                if (chestManager != null) {
                    String owner = chestManager.getChestOwner(b);
                    if (owner == null) {
                        // El primer jugador en abrirlo lo reclama automaticamente
                        chestManager.claimChest(b, p.getName());
                        p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Cofres] "
                                + ChatColor.YELLOW + "¡Has reclamado este cofre! Ahora eres su dueño ("
                                + ChatColor.GOLD + p.getName() + ChatColor.YELLOW + ") y solo tú puedes abrirlo.");
                        try {
                            p.playSound(p.getLocation(), Sound.ORB_PICKUP, 1.0f, 1.2f);
                        } catch (Exception ignored) {}
                    } else {
                        boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                        boolean isMaster = p.getName().equalsIgnoreCase(ownerName);

                        if (isChestOwner) {
                            // Permitido: el dueño abre su propio cofre
                        } else if (isMaster) {
                            // Permitido: Xavi abre el cofre con aviso informativo
                            p.sendMessage(ChatColor.GOLD + "[Cofre Admin] "
                                    + ChatColor.YELLOW + "Abriendo cofre de: " + ChatColor.WHITE + owner);
                        } else {
                            // Denegado: otro jugador intenta abrirlo
                            event.setCancelled(true);
                            event.setUseInteractedBlock(Event.Result.DENY);
                            event.setUseItemInHand(Event.Result.DENY);
                            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Cofres] "
                                    + ChatColor.RED + "¡Este cofre pertenece a " + ChatColor.GOLD + owner
                                    + ChatColor.RED + "! Solo su dueño puede abrirlo.");
                            try {
                                p.playSound(p.getLocation(), Sound.CHEST_CLOSE, 1.0f, 0.5f);
                            } catch (Exception ignored) {}
                            final Player playerObj = p;
                            Bukkit.getScheduler().runTask(this, new Runnable() {
                                @Override
                                public void run() {
                                    playerObj.closeInventory();
                                }
                            });
                            return;
                        }
                    }
                }
            }

            Zone z = getZoneAt(b.getLocation());
            if (z != null && !isTrusted(p)) {
                if (event.getItem() != null && (event.getItem().getType() == Material.FLINT_AND_STEEL
                        || event.getItem().getType() == Material.FIREBALL
                        || event.getItem().getType() == Material.LAVA_BUCKET)) {
                    event.setCancelled(true);
                    punishHouseGriefer(p, "quemar la casa", z);
                    return;
                }
                if (protectHouseChests && (type == Material.CHEST || type == Material.TRAPPED_CHEST
                        || type == Material.FURNACE || type == Material.BURNING_FURNACE
                        || type == Material.ANVIL || type == Material.ENCHANTMENT_TABLE
                        || type == Material.BREWING_STAND || type == Material.HOPPER
                        || type == Material.DISPENSER || type == Material.DROPPER)) {
                    if (isPublicBlock(b)) {
                        // Public furnace: allow everyone to open and cook!
                        return;
                    }
                    event.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "[Casa de Xavi] ¡Los cofres y hornos de Xavi estan bloqueados!");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        final Player p = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();

        Block b = null;
        if (holder instanceof Chest) {
            b = ((Chest) holder).getBlock();
        } else if (holder instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) holder;
            if (dc.getLeftSide() instanceof Chest) {
                b = ((Chest) dc.getLeftSide()).getBlock();
            }
        }

        if (b != null && chestManager != null) {
            String owner = chestManager.getChestOwner(b);
            if (owner == null) {
                chestManager.claimChest(b, p.getName());
                p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Cofres] "
                        + ChatColor.YELLOW + "¡Has reclamado este cofre! Ahora eres su dueño ("
                        + ChatColor.GOLD + p.getName() + ChatColor.YELLOW + ") y solo tú puedes abrirlo.");
                try {
                    p.playSound(p.getLocation(), Sound.ORB_PICKUP, 1.0f, 1.2f);
                } catch (Exception ignored) {}
            } else {
                boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                if (!isChestOwner && !isMaster) {
                    event.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "[Cofres] "
                            + ChatColor.RED + "¡Este cofre pertenece a " + ChatColor.GOLD + owner
                            + ChatColor.RED + "! Solo su dueño puede abrirlo.");
                    try {
                        p.playSound(p.getLocation(), Sound.CHEST_CLOSE, 1.0f, 0.5f);
                    } catch (Exception ignored) {}
                    Bukkit.getScheduler().runTask(this, new Runnable() {
                        @Override
                        public void run() {
                            p.closeInventory();
                        }
                    });
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        final Player p = (Player) event.getWhoClicked();

        // 1. Nerf: proteccion de ranuras bloqueadas para ProtenGavila u otros nerfeados
        if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
            ItemStack clicked = event.getCurrentItem();
            ItemStack cursor = event.getCursor();
            if (nerfManager.isLockedItem(clicked) || nerfManager.isLockedItem(cursor)) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                p.sendMessage(ChatColor.RED + "[Nerf] ¡Esa ranura está bloqueada por tu nerfeo (50% de inventario)!");
                return;
            }
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory())) {
                if (nerfManager.isLockedSlot(event.getSlot())) {
                    event.setCancelled(true);
                    event.setResult(Event.Result.DENY);
                    p.sendMessage(ChatColor.RED + "[Nerf] ¡Esa ranura está bloqueada por tu nerfeo (50% de inventario)!");
                    return;
                }
            }
            if (event.isShiftClick()) {
                Bukkit.getScheduler().runTask(this, new Runnable() {
                    @Override
                    public void run() {
                        if (p.isOnline() && nerfManager.isNerfed(p.getName())) {
                            nerfManager.enforceLockedSlots(p);
                        }
                    }
                });
            }
        }

        // 2. Cofres: impedir que nadie robe items de un cofre privado
        InventoryHolder holder = event.getInventory().getHolder();
        Block b = null;
        if (holder instanceof Chest) {
            b = ((Chest) holder).getBlock();
        } else if (holder instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) holder;
            if (dc.getLeftSide() instanceof Chest) {
                b = ((Chest) dc.getLeftSide()).getBlock();
            }
        }

        if (b != null && chestManager != null) {
            String owner = chestManager.getChestOwner(b);
            if (owner != null) {
                boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                if (!isChestOwner && !isMaster) {
                    event.setCancelled(true);
                    event.setResult(Event.Result.DENY);
                    p.closeInventory();
                    p.sendMessage(ChatColor.RED + "[Cofres] ¡No puedes tomar objetos del cofre de " + ChatColor.GOLD + owner + ChatColor.RED + "!");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        final Player p = (Player) event.getWhoClicked();

        if (nerfManager != null && nerfManager.isNerfed(p.getName())) {
            if (nerfManager.isLockedItem(event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
        }

        InventoryHolder holder = event.getInventory().getHolder();
        Block b = null;
        if (holder instanceof Chest) {
            b = ((Chest) holder).getBlock();
        } else if (holder instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) holder;
            if (dc.getLeftSide() instanceof Chest) {
                b = ((Chest) dc.getLeftSide()).getBlock();
            }
        }
        if (b != null && chestManager != null) {
            String owner = chestManager.getChestOwner(b);
            if (owner != null) {
                boolean isChestOwner = owner.equalsIgnoreCase(p.getName());
                boolean isMaster = p.getName().equalsIgnoreCase(ownerName);
                if (!isChestOwner && !isMaster) {
                    event.setCancelled(true);
                    event.setResult(Event.Result.DENY);
                    p.closeInventory();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (chestManager == null) return;
        InventoryHolder holder = event.getSource().getHolder();
        Block b = null;
        if (holder instanceof Chest) {
            b = ((Chest) holder).getBlock();
        } else if (holder instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) holder;
            if (dc.getLeftSide() instanceof Chest) {
                b = ((Chest) dc.getLeftSide()).getBlock();
            }
        }
        if (b != null) {
            String owner = chestManager.getChestOwner(b);
            if (owner != null && !owner.trim().isEmpty()) {
                // Prevenir que tolvas o dispensadores vacien cofres ajenos
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (antiExplosionDestroy) {
            event.blockList().clear();
            return;
        }
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (chestManager != null && chestManager.getChestOwner(b) != null) {
                it.remove();
            } else if (getZoneAt(b.getLocation()) != null) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (antiFireSpread || getZoneAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (getZoneAt(event.getBlock().getLocation()) != null) {
            Player p = event.getPlayer();
            if (p != null && !isTrusted(p)) {
                event.setCancelled(true);
                punishHouseGriefer(p, "prender fuego", getZoneAt(event.getBlock().getLocation()));
                return;
            } else if (p == null) {
                event.setCancelled(true);
                return;
            }
        }
        if (antiFireSpread && event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isJosefa(event.getRightClicked())) {
            Player p = event.getPlayer();
            PetManager.PetData pd = (petManager != null ? petManager.getPetData(event.getRightClicked()) : null);
            String petOwner = (pd != null ? pd.owner : ownerName);
            String petName = (pd != null ? pd.name : "Josefa");
            if (!p.getName().equalsIgnoreCase(petOwner) && !p.getName().equalsIgnoreCase(ownerName) && !isTrusted(p)) {
                event.setCancelled(true);
                p.sendMessage(ChatColor.RED + "¡No toques a " + petName + "! Es la mascota sagrada de " + petOwner + ".");
            }
        }
    }

    public boolean isJosefa(Entity e) {
        if (e == null) return false;
        if (petManager != null && petManager.isProtectedPet(e)) return true;
        if (e instanceof Sheep) {
            Sheep s = (Sheep) e;
            String name = s.getCustomName();
            if (name != null && ChatColor.stripColor(name).equalsIgnoreCase("Josefa")) {
                return true;
            }
            if (getZoneAt(s.getLocation()) != null) {
                return true;
            }
        }
        return false;
    }

    public void banPlayer(String playerName, String reason) {
        if (playerName.equalsIgnoreCase(ownerName)) return;
        Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, null, "XaviAdminProtect");
        final Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            Bukkit.getScheduler().runTask(this, new Runnable() {
                @Override
                public void run() {
                    online.kickPlayer(ChatColor.DARK_RED + "" + ChatColor.BOLD + "¡HAS SIDO BANEADO PERMANENTEMENTE DEL SERVIDOR!\\n\\n"
                            + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + reason);
                }
            });
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "⛔ [AUTO-BAN] "
                + ChatColor.YELLOW + playerName + ChatColor.RED + " ha sido BANEADO PERMANENTEMENTE por " + reason + "!");
        getLogger().info("[AUTO-BAN] Jugador " + playerName + " baneado por " + reason);
    }

    private boolean isHouseMaterial(Material m) {
        if (m == null || m == Material.AIR) return false;
        String name = m.name();
        return name.contains("WOOD") || name.contains("LOG") || name.contains("PLANK")
                || name.contains("STEP") || name.contains("STAIRS")
                || name.contains("GLASS") || name.contains("DOOR") || name.contains("TRAP")
                || name.contains("FENCE") || name.contains("GATE")
                || name.contains("BRICK") || name.contains("COBBLE") || name.contains("SMOOTH")
                || name.contains("CHEST") || name.contains("FURNACE") || name.contains("WORKBENCH")
                || name.contains("CARPET") || name.contains("TORCH") || name.contains("BED")
                || name.contains("LADDER") || name.contains("SIGN") || name.contains("WOOL")
                || name.contains("ANVIL") || name.contains("BOOKSHELF") || name.contains("QUARTZ")
                || name.contains("IRON_BARS") || name.contains("CLAY");
    }

    private boolean isFenceMaterial(Material m) {
        if (m == null) return false;
        String name = m.name();
        return name.contains("FENCE") && !name.contains("GATE");
    }

    public void scanAndProtectHouse(CommandSender sender) {
        World w = Bukkit.getWorld("world");
        if (w == null) {
            sender.sendMessage(ChatColor.RED + "Error: Mundo 'world' no encontrado.");
            return;
        }

        // Core house seed positions (Xavi's chests and key house coordinates)
        int[][] seedPoints = {
            {-278, 76, -9}, {-279, 76, -9},
            {-277, 76, -2}, {-278, 76, -2},
            {-268, 80, -8}, {-270, 77, -3},
            {-282, 77, -5}
        };

        int coreCenterX = -275;
        int coreCenterZ = -5;

        java.util.Queue<Block> queue = new java.util.LinkedList<Block>();
        java.util.Set<String> visited = new java.util.HashSet<String>();

        for (int[] pt : seedPoints) {
            Block b = w.getBlockAt(pt[0], pt[1], pt[2]);
            String key = pt[0] + "," + pt[1] + "," + pt[2];
            if (!visited.contains(key)) {
                visited.add(key);
                queue.add(b);
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int houseBlockCount = 0;
        Location bestFence = null;

        // 26 connected neighbor offsets (includes diagonals to bridge stairs/corners/slabs)
        java.util.List<int[]> neighborDirs = new java.util.ArrayList<int[]>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    neighborDirs.add(new int[]{dx, dy, dz});
                }
            }
        }

        while (!queue.isEmpty()) {
            Block curr = queue.poll();
            int cx = curr.getX();
            int cy = curr.getY();
            int cz = curr.getZ();

            // Distance limit from core center to stay strictly within house bounds
            int dX = cx - coreCenterX;
            int dZ = cz - coreCenterZ;
            if (dX * dX + dZ * dZ > 24 * 24) continue;
            if (cy < 60 || cy > 105) continue;

            Material m = curr.getType();
            if (isHouseMaterial(m)) {
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                if (cz < minZ) minZ = cz;
                if (cz > maxZ) maxZ = cz;
                houseBlockCount++;

                if (isFenceMaterial(m) && bestFence == null) {
                    bestFence = curr.getLocation();
                }

                // Add unvisited neighbor blocks
                for (int[] dir : neighborDirs) {
                    int nx = cx + dir[0];
                    int ny = cy + dir[1];
                    int nz = cz + dir[2];
                    String key = nx + "," + ny + "," + nz;
                    if (!visited.contains(key)) {
                        visited.add(key);
                        Block nb = w.getBlockAt(nx, ny, nz);
                        Material nMat = nb.getType();
                        // Only flood fill connected house materials
                        if (isHouseMaterial(nMat)) {
                            queue.add(nb);
                        }
                    }
                }
            }
        }

        if (houseBlockCount < 10) {
            sender.sendMessage(ChatColor.RED + "No se encontraron suficientes bloques de casa para delimitar (detectados: " + houseBlockCount + ").");
            return;
        }

        // Bounding box strictly encompassing the connected house structure
        Zone main = new Zone("CasaPrincipal", w.getName(), minX, maxX, minY, maxY, minZ, maxZ);
        zones.clear();
        zones.add(main);
        saveSettings();

        sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ [Casa Protegida Actualizada]");
        sender.sendMessage(ChatColor.YELLOW + "  Perímetro exacto de la nueva casa:");
        sender.sendMessage(ChatColor.AQUA + "  X: " + minX + " a " + maxX + " (" + (maxX - minX + 1) + " bloques de ancho)");
        sender.sendMessage(ChatColor.AQUA + "  Y: " + minY + " a " + maxY + " (" + (maxY - minY + 1) + " bloques de alto)");
        sender.sendMessage(ChatColor.AQUA + "  Z: " + minZ + " a " + maxZ + " (" + (maxZ - minZ + 1) + " bloques de largo)");
        sender.sendMessage(ChatColor.GREEN + "  Total bloques estructurales conectados: " + houseBlockCount + ". ¡Ningún bloque exterior queda protegido!");

        setupJosefa(w, main, bestFence, sender);
    }

    public void setupJosefa(World w, Zone house, Location fenceLoc, CommandSender sender) {
        Sheep josefa = null;

        for (Entity e : w.getEntities()) {
            if (e instanceof Sheep) {
                Sheep s = (Sheep) e;
                if (isJosefa(s)) {
                    josefa = s;
                    break;
                }
            }
        }

        if (josefa == null && house != null) {
            for (Entity e : w.getEntities()) {
                if (e instanceof Sheep) {
                    Sheep s = (Sheep) e;
                    if (house.contains(s.getLocation())) {
                        josefa = s;
                        break;
                    }
                }
            }
        }

        // Search around Josefa or house for the closest fence if not provided
        if (fenceLoc == null) {
            Location center = (josefa != null ? josefa.getLocation() : (house != null ? new Location(w, (house.minX + house.maxX)/2.0, (house.minY + house.maxY)/2.0, (house.minZ + house.maxZ)/2.0) : new Location(w, -270, 77, -3)));
            int searchR = 8;
            double minDist = Double.MAX_VALUE;
            for (int x = center.getBlockX() - searchR; x <= center.getBlockX() + searchR; x++) {
                for (int y = Math.max(60, center.getBlockY() - 3); y <= Math.min(100, center.getBlockY() + 4); y++) {
                    for (int z = center.getBlockZ() - searchR; z <= center.getBlockZ() + searchR; z++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (isFenceMaterial(b.getType())) {
                            double dist = b.getLocation().distanceSquared(center);
                            if (dist < minDist) {
                                minDist = dist;
                                fenceLoc = b.getLocation();
                            }
                        }
                    }
                }
            }
        }

        if (josefa != null) {
            josefa.setCustomName(ChatColor.GREEN + "" + ChatColor.BOLD + "Josefa");
            josefa.setCustomNameVisible(true);
            josefa.setRemoveWhenFarAway(false);
            josefa.setAgeLock(true);
            josefa.setHealth(josefa.getMaxHealth());

            if (fenceLoc != null && !josefa.isLeashed()) {
                attachToFence(josefa, fenceLoc);
            }
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "✔ [Josefa] Mascota Josefa configurada con nombre verde flotante en X=" + josefa.getLocation().getBlockX() + ", Y=" + josefa.getLocation().getBlockY() + ", Z=" + josefa.getLocation().getBlockZ() + (josefa.isLeashed() ? " (Atada a su valla)" : (fenceLoc != null ? " (Atada a valla)" : "")));
            }
        } else {
            Location spawnLoc;
            if (fenceLoc != null) {
                spawnLoc = fenceLoc.clone().add(0.5, 0.2, 0.5);
                if (spawnLoc.getBlock().getType().isSolid()) {
                    spawnLoc.add(0, 1, 0);
                }
            } else if (house != null) {
                spawnLoc = new Location(w, (house.minX + house.maxX)/2.0, house.minY + 1.0, (house.minZ + house.maxZ)/2.0);
            } else {
                spawnLoc = new Location(w, -270.5, 77.0, -3.5);
            }

            Sheep newSheep = (Sheep) w.spawnEntity(spawnLoc, EntityType.SHEEP);
            newSheep.setColor(DyeColor.WHITE);
            newSheep.setCustomName(ChatColor.GREEN + "" + ChatColor.BOLD + "Josefa");
            newSheep.setCustomNameVisible(true);
            newSheep.setRemoveWhenFarAway(false);
            newSheep.setAgeLock(true);

            if (fenceLoc != null) {
                attachToFence(newSheep, fenceLoc);
            }
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "✔ [Josefa] ¡Josefa ha sido invocada con su nombre verde flotante y atada a su valla!");
            }
        }
    }

    private void attachToFence(Sheep sheep, Location fenceLoc) {
        try {
            LeashHitch knot = null;
            for (Entity e : fenceLoc.getWorld().getNearbyEntities(fenceLoc, 1.5, 1.5, 1.5)) {
                if (e instanceof LeashHitch) {
                    knot = (LeashHitch) e;
                    break;
                }
            }
            if (knot == null) {
                knot = fenceLoc.getWorld().spawn(fenceLoc, LeashHitch.class);
            }
            if (knot != null) {
                sheep.setLeashHolder(knot);
            }
        } catch (Exception e) {
            getLogger().warning("[Josefa] No se pudo atar a la valla automáticamente: " + e.getMessage());
        }
    }

        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String c = cmd.getName().toLowerCase();

        if (c.equals("scancasa") || c.equals("ajustarcasa") || c.equals("escanearcasa")) {
            scanAndProtectHouse(sender);
            return true;
        }

        // ================= SISTEMA DE MASCOTAS PROTEGIDAS (ESTILO JOSEFA) =================
        if (c.equals("mascota") || c.equals("protegermascota") || c.equals("nombrarmascota") || c.equals("setmascota") || c.equals("pet")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Solo los jugadores pueden registrar mascotas mirándolas.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /mascota <nombre>");
                sender.sendMessage(ChatColor.GRAY + "Mira fijamente a cualquier animal (a menos de 6 bloques) y escribe su nombre para protegerlo como a Josefa.");
                return true;
            }
            Player p = (Player) sender;
            LivingEntity target = getTargetAnimal(p, 6);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "Debes mirar fijamente a un animal (oveja, vaca, cerdo, caballo, perro, gato, etc.) a menos de 6 bloques.");
                return true;
            }

            String petName = args[0].trim();
            if (petManager != null) {
                boolean ok = petManager.registerPet(target, petName, p.getName());
                if (ok) {
                    p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ [Mascota Protegida Activada]");
                    p.sendMessage(ChatColor.YELLOW + "  Nombre: " + ChatColor.AQUA + "" + ChatColor.BOLD + petName);
                    p.sendMessage(ChatColor.YELLOW + "  Dueño: " + ChatColor.GOLD + p.getName());
                    p.sendMessage(ChatColor.GREEN + "  Seguridad de Josefa: " + ChatColor.WHITE + "Inmunidad ambiental total, nombre flotante verde, auto-ban permanente a agresores y reaparición automática.");
                    p.playSound(p.getLocation(), Sound.LEVEL_UP, 1.0f, 1.5f);
                } else {
                    p.sendMessage(ChatColor.RED + "Error al registrar la mascota.");
                }
            }
            return true;
        }

        if (c.equals("listamascotas") || c.equals("vermascotas") || c.equals("pets")) {
            if (petManager == null) {
                sender.sendMessage(ChatColor.RED + "Gestor de mascotas no inicializado.");
                return true;
            }
            List<PetManager.PetData> pList = petManager.getAllPets();
            sender.sendMessage(ChatColor.GOLD + "=== Mascotas Protegidas en el Servidor (" + pList.size() + ") ===");
            for (PetManager.PetData pd : pList) {
                sender.sendMessage(ChatColor.GREEN + "• " + ChatColor.BOLD + pd.name + ChatColor.RESET + ChatColor.YELLOW + " (" + pd.entityType + ") - Dueño: " + ChatColor.GOLD + pd.owner + ChatColor.GRAY + " en [" + (int)pd.x + ", " + (int)pd.y + ", " + (int)pd.z + "]");
            }
            return true;
        }

        if (c.equals("liberarmascota") || c.equals("desregistrarmascota")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /liberarmascota <nombre>");
                return true;
            }
            if (petManager == null) return true;
            PetManager.PetData pd = petManager.getPetByName(args[0]);
            if (pd == null) {
                sender.sendMessage(ChatColor.RED + "No se encontró ninguna mascota protegida con el nombre '" + args[0] + "'.");
                return true;
            }
            if (!sender.getName().equalsIgnoreCase(pd.owner) && !isOwner(sender)) {
                sender.sendMessage(ChatColor.RED + "Solo el dueño (" + pd.owner + ") o Xavi pueden liberar a esta mascota.");
                return true;
            }
            petManager.unregisterPet(pd.name);
            sender.sendMessage(ChatColor.YELLOW + "La mascota '" + pd.name + "' ha sido liberada y ya no cuenta con protección especial.");
            return true;
        }

        // ================= COMANDOS DEL POLICÍA IA =================
        if (c.equals("policia") || c.equals("police") || c.equals("cop") || c.equals("agentetorres")) {
            if (policeManager == null) {
                sender.sendMessage(ChatColor.RED + "Gestor de policía no inicializado.");
                return true;
            }
            String sub = args.length > 0 ? args[0].toLowerCase() : "info";

            if (sub.equals("spawn") || sub.equals("generar")) {
                if (!isOwner(sender)) {
                    sender.sendMessage(ChatColor.RED + "Solo Xavi o consola pueden reaparecer al policía manualmente.");
                    return true;
                }
                policeManager.setKilledThisSession(false);
                policeManager.spawnPolice();
                sender.sendMessage(ChatColor.BLUE + "🚔 Oficial de policía desplegado en servicio.");
                return true;
            }

            if (sub.equals("revivir") || sub.equals("revive")) {
                if (!isOwner(sender)) {
                    sender.sendMessage(ChatColor.RED + "Solo Xavi o consola pueden revivir al policía.");
                    return true;
                }
                policeManager.setKilledThisSession(false);
                policeManager.spawnPolice();
                sender.sendMessage(ChatColor.BLUE + "🚔 Oficial de policía revivido y patrullando.");
                return true;
            }

            if (sub.equals("tp")) {
                if (!(sender instanceof Player)) return true;
                Player p = (Player) sender;
                LivingEntity cop = policeManager.getPoliceEntity();
                if (cop != null) {
                    p.teleport(cop.getLocation());
                    p.sendMessage(ChatColor.BLUE + "🚔 Te has teletransportado al Oficial de Policía.");
                } else {
                    p.sendMessage(ChatColor.RED + "El oficial de policía no está en el mundo en este momento.");
                }
                return true;
            }

            // info
            LivingEntity cop = policeManager.getPoliceEntity();
            sender.sendMessage(ChatColor.BLUE + "=== Estado del Oficial de Policía BOT ===");
            if (policeManager.isKilledThisSession()) {
                sender.sendMessage(ChatColor.RED + "Estado: ABATIDO EN ESTA SESIÓN (No reaparecerá hasta que se apague y encienda el server)");
            } else if (cop != null && cop.isValid() && !cop.isDead()) {
                sender.sendMessage(ChatColor.GREEN + "Estado: EN SERVICIO ACTIVO (Patrullando la zona)");
                sender.sendMessage(ChatColor.YELLOW + "Ubicación: X=" + cop.getLocation().getBlockX() + ", Y=" + cop.getLocation().getBlockY() + ", Z=" + cop.getLocation().getBlockZ());
                sender.sendMessage(ChatColor.AQUA + "Salud: " + (int) cop.getHealth() + " / " + (int) cop.getMaxHealth());
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Estado: Esperando jugadores para entrar en servicio.");
            }
            return true;
        }

        if (c.equals("online") || c.equals("jugadores") || c.equals("quien") || c.equals("conectados") || c.equals("onlineplayers")) {
            sendOnlinePlayersList(sender);
            return true;
        }

        if (c.equals("spawnjosefa") || c.equals("josefa")) {
            World w = Bukkit.getWorld("world");
            Zone house = zones.isEmpty() ? null : zones.get(0);
            setupJosefa(w, house, null, sender);
            return true;
        }


        boolean isPublicCmd = c.equals("unirse") || c.equals("unirme") || c.equals("join") || c.equals("entrar") || c.equals("aceptar")
                || c.equals("crearclan") || c.equals("crearalianza") || c.equals("clancrear") || c.equals("fundarclan")
                || c.equals("invitar") || c.equals("invite")
                || c.equals("salirclan") || c.equals("saliralianza")
                || c.equals("alianza") || c.equals("alliance") || c.equals("clan") || c.equals("faccion")
                || c.equals("ac") || c.equals("alliancechat") || c.equals("chatclan")
                || c.equals("honor") || c.equals("mihonor") || c.equals("reputacion")
                || c.equals("donar") || c.equals("giveitem") || c.equals("regalar")
                || c.equals("forajidos") || c.equals("topforajidos") || c.equals("outlaws") || c.equals("criminales")
                || c.equals("heroes") || c.equals("topheroes")
                || c.equals("infocofre") || c.equals("vercofre") || c.equals("cofreinfo")
                || c.equals("online") || c.equals("jugadores") || c.equals("quien") || c.equals("conectados") || c.equals("onlineplayers")
                || c.equals("mascota") || c.equals("protegermascota") || c.equals("nombrarmascota") || c.equals("setmascota") || c.equals("pet")
                || c.equals("listamascotas") || c.equals("vermascotas") || c.equals("pets")
                || c.equals("liberarmascota") || c.equals("desregistrarmascota")
                || c.equals("policia") || c.equals("police") || c.equals("cop") || c.equals("agentetorres");

        if (!isPublicCmd && !isOwner(sender)) {
            sender.sendMessage(ChatColor.RED + "Solo Xavi o la Consola de Control pueden usar este comando.");
            return true;
        }

        if (c.equals("ban")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /ban <jugador> [motivo]");
                return true;
            }
            String target = args[0];
            if (target.equalsIgnoreCase(ownerName)) {
                sender.sendMessage(ChatColor.RED + "¡No puedes banear a Xavi!");
                return true;
            }
            StringBuilder reasonBuilder = new StringBuilder();
            if (args.length > 1) {
                for (int i = 1; i < args.length; i++) {
                    reasonBuilder.append(args[i]).append(" ");
                }
            } else {
                reasonBuilder.append("Baneado por Xavi");
            }
            String reason = reasonBuilder.toString().trim();
            Bukkit.getBanList(BanList.Type.NAME).addBan(target, reason, null, sender.getName());
            Player online = Bukkit.getPlayerExact(target);
            if (online != null) {
                online.kickPlayer(ChatColor.RED + "" + ChatColor.BOLD + "¡HAS SIDO BANEADO DEL SERVIDOR!\n\n"
                        + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + reason);
            }
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Servidor] " + ChatColor.YELLOW + target
                    + ChatColor.RED + " ha sido BANEADO por Xavi. (" + reason + ")");
            return true;
        }

        if (c.equals("unban")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /unban <jugador>");
                return true;
            }
            String target = args[0];
            BanList nameBans = Bukkit.getBanList(BanList.Type.NAME);
            BanList ipBans = Bukkit.getBanList(BanList.Type.IP);

            // Case-insensitive name unban
            for (BanEntry entry : new ArrayList<BanEntry>(nameBans.getBanEntries())) {
                if (entry.getTarget().equalsIgnoreCase(target)) {
                    nameBans.pardon(entry.getTarget());
                }
            }
            nameBans.pardon(target);

            // Also pardon any lingering IP bans
            for (BanEntry entry : new ArrayList<BanEntry>(ipBans.getBanEntries())) {
                ipBans.pardon(entry.getTarget());
            }

            // Sync with vanilla Bukkit pardon
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:pardon " + target);
            } catch (Exception ignored) {}

            playerWarnings.remove(target.toLowerCase());
            saveWarnings();

            sender.sendMessage(ChatColor.GREEN + "[XaviAdminProtect] Jugador " + ChatColor.YELLOW + target + ChatColor.GREEN + " ha sido DESBANEADO completamente (nombre e IP limpios, advertencias reiniciadas).");
            Bukkit.broadcastMessage(ChatColor.GREEN + "[Servidor] Xavi ha DESBANEADO a " + ChatColor.YELLOW + target + ChatColor.GREEN + ". ¡Ya puedes entrar!");
            return true;
        }

        if (c.equals("baneados")) {
            Set<BanEntry> entries = Bukkit.getBanList(BanList.Type.NAME).getBanEntries();
            if (entries.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "No hay ningun jugador baneado actualmente.");
            } else {
                sender.sendMessage(ChatColor.GOLD + "=== Jugadores Baneados (" + entries.size() + ") ===");
                for (BanEntry e : entries) {
                    sender.sendMessage(ChatColor.YELLOW + "- " + e.getTarget() + ChatColor.GRAY + " | Motivo: " + ChatColor.WHITE + e.getReason());
                }
            }
            return true;
        }

        if (c.equals("expulsar")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /expulsar <jugador> [motivo]");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Ese jugador no esta conectado.");
                return true;
            }
            String reason = "Expulsado por Xavi";
            if (args.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
                reason = sb.toString().trim();
            }
            target.kickPlayer(ChatColor.RED + "Has sido expulsado del servidor.\n" + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + reason);
            sender.sendMessage(ChatColor.GREEN + "Jugador " + target.getName() + " expulsado.");
            return true;
        }

        if (c.equals("protegercasa")) {
            int radius = 32;
            if (args.length >= 1) {
                try {
                    radius = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "El radio debe ser un numero (ej. /protegercasa 35).");
                    return true;
                }
            }
            Location loc;
            if (sender instanceof Player) {
                loc = ((Player) sender).getLocation();
            } else if (args.length >= 4) {
                loc = new Location(Bukkit.getWorld("world"), Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            } else {
                Player xavi = Bukkit.getPlayerExact(ownerName);
                if (xavi != null) {
                    loc = xavi.getLocation();
                } else {
                    loc = new Location(Bukkit.getWorld("world"), -276, 67, -10);
                }
            }
            Zone main = new Zone("CasaPrincipal", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), radius);
            boolean replaced = false;
            for (int i = 0; i < zones.size(); i++) {
                if (zones.get(i).name.equalsIgnoreCase("CasaPrincipal")) {
                    zones.set(i, main);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) zones.add(main);
            houseProtectionEnabled = true;
            saveSettings();
            sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "[Casa Protegida] "
                    + ChatColor.YELLOW + "Zona principal fijada en X=" + main.x + ", Y=" + main.y + ", Z=" + main.z
                    + " (Radio: " + main.radius + " bloques). Auto-Ban: " + (autoBanOnGrief ? "ACTIVADO" : "DESACTIVADO"));
            return true;
        }

        if (c.equals("addcasa")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usa este comando dentro del juego en la ubicacion de tu nueva construccion.");
                return true;
            }
            Player p = (Player) sender;
            int radius = 25;
            if (args.length >= 1) {
                try { radius = Integer.parseInt(args[0]); } catch (Exception ignored) {}
            }
            String zName = "Zona" + (zones.size() + 1);
            Location loc = p.getLocation();
            Zone nz = new Zone(zName, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), radius);
            zones.add(nz);
            saveSettings();
            sender.sendMessage(ChatColor.GREEN + "[Casa Protegida] Añadida " + zName + " en X=" + nz.x + ", Z=" + nz.z + " (Radio: " + radius + ").");
            return true;
        }

        if (c.equals("autobancasa") || c.equals("autokickcasa")) {
            if (args.length < 1) {
                autoBanOnGrief = !autoBanOnGrief;
            } else {
                autoBanOnGrief = args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("si");
            }
            saveSettings();
            sender.sendMessage(ChatColor.GOLD + "[XaviAdminProtect] Auto-Kick al tocar tu casa: "
                    + (autoBanOnGrief ? ChatColor.GREEN + "ACTIVADO (Expulsa sin banear)" : ChatColor.RED + "DESACTIVADO (solo bloquea sin expulsar)"));
            return true;
        }

        if (c.equals("keepinventory") || c.equals("guardarinventario")) {
            if (args.length < 1 || args[0].equalsIgnoreCase("toggle")) {
                keepInventoryEnabled = !keepInventoryEnabled;
            } else {
                keepInventoryEnabled = args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("si") || args[0].equalsIgnoreCase("1");
            }
            for (World w : Bukkit.getWorlds()) {
                w.setGameRuleValue("keepInventory", keepInventoryEnabled ? "true" : "false");
            }
            saveSettings();
            String statusText = keepInventoryEnabled 
                    ? ChatColor.GREEN + "ACTIVADO (No se pierden objetos al morir)" 
                    : ChatColor.RED + "DESACTIVADO (Sueltas todo el inventario al morir o si te matan)";
            sender.sendMessage(ChatColor.GOLD + "[XaviAdminProtect] Conservar inventario al morir: " + statusText);
            Bukkit.broadcastMessage(ChatColor.GOLD + "[REGLA DEL SERVIDOR] " + ChatColor.YELLOW + "Conservar inventario al morir: " + statusText);
            return true;
        }

        if (c.equals("cofrescasa")) {
            if (args.length < 1) {
                protectHouseChests = !protectHouseChests;
            } else {
                protectHouseChests = args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("si");
            }
            saveSettings();
            sender.sendMessage(ChatColor.GOLD + "[XaviAdminProtect] Proteccion de cofres/hornos en tu casa: "
                    + (protectHouseChests ? ChatColor.GREEN + "ACTIVADO" : ChatColor.RED + "DESACTIVADO"));
            return true;
        }

        if (c.equals("amigos")) {
            if (args.length < 1 || args[0].equalsIgnoreCase("list")) {
                sender.sendMessage(ChatColor.GOLD + "Amigos con permiso en tu casa: " + ChatColor.WHITE + trustedFriends.toString());
                sender.sendMessage(ChatColor.GRAY + "Usa /amigos add <nombre> o /amigos remove <nombre>");
                return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("add")) {
                trustedFriends.add(args[1].toLowerCase());
                saveSettings();
                sender.sendMessage(ChatColor.GREEN + "Ahora " + ChatColor.YELLOW + args[1] + ChatColor.GREEN + " tiene permiso para construir en tu casa sin ser baneado.");
                return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("remove")) {
                trustedFriends.remove(args[1].toLowerCase());
                saveSettings();
                sender.sendMessage(ChatColor.YELLOW + args[1] + ChatColor.RED + " ya NO tiene permiso en tu casa.");
                return true;
            }
            return true;
        }

        if (c.equals("infocasa")) {
            sender.sendMessage(ChatColor.GOLD + "=== Estado de Proteccion XaviAdminProtect ===");
            sender.sendMessage(ChatColor.YELLOW + "Proteccion Casa: " + (houseProtectionEnabled ? ChatColor.GREEN + "ACTIVA" : ChatColor.RED + "INACTIVA"));
            sender.sendMessage(ChatColor.YELLOW + "Auto-Ban Griefers: " + (autoBanOnGrief ? ChatColor.GREEN + "ACTIVO" : ChatColor.RED + "INACTIVO"));
            sender.sendMessage(ChatColor.YELLOW + "Sistema Advertencias (/warn): " + ChatColor.GREEN + "ACTIVO (Auto-Ban a las 4 advertencias)");
            sender.sendMessage(ChatColor.YELLOW + "Auto-Apagado por Inactividad: " + (idleLimitMinutes > 0 ? ChatColor.GREEN + "ACTIVADO (" + idleLimitMinutes + " min sin jugadores)" : ChatColor.RED + "DESACTIVADO"));
            sender.sendMessage(ChatColor.YELLOW + "Cofres Protegidos: " + (protectHouseChests ? ChatColor.GREEN + "SI" : ChatColor.RED + "NO"));
            sender.sendMessage(ChatColor.YELLOW + "Anti-Explosiones (Creeper/TNT): " + (antiExplosionDestroy ? ChatColor.GREEN + "SI" : ChatColor.RED + "NO"));
            sender.sendMessage(ChatColor.YELLOW + "Amigos con permiso: " + ChatColor.WHITE + trustedFriends.toString());
            for (Zone z : zones) {
                if (z.isBox) {
                    sender.sendMessage(ChatColor.AQUA + "- " + z.name + " [Perímetro Exacto]: X=(" + z.minX + ".." + z.maxX + ") Y=(" + z.minY + ".." + z.maxY + ") Z=(" + z.minZ + ".." + z.maxZ + ")");
                } else {
                    sender.sendMessage(ChatColor.AQUA + "- " + z.name + ": Centro=(" + z.x + ", " + z.y + ", " + z.z + ") Radio=" + z.radius + " bloques");
                }
            }
            return true;
        }

        if (c.equals("casa")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Solo un jugador puede teletransportarse.");
                return true;
            }
            Player p = (Player) sender;
            if (!zones.isEmpty()) {
                Zone z = zones.get(0);
                World w = Bukkit.getWorld(z.world);
                if (w != null) {
                    int safeY = w.getHighestBlockYAt(z.x, z.z);
                    if (safeY < z.y) safeY = z.y;
                    p.teleport(new Location(w, z.x + 0.5, safeY + 1, z.z + 0.5));
                    p.sendMessage(ChatColor.GREEN + "¡Teletransportado a tu casa!");
                }
            }
            return true;
        }

        if (c.equals("congelar")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /congelar <jugador>");
                return true;
            }
            String target = args[0].toLowerCase();
            if (frozenPlayers.contains(target)) {
                frozenPlayers.remove(target);
                sender.sendMessage(ChatColor.GREEN + "Has DESCONGELADO a " + args[0] + ".");
                Player p = Bukkit.getPlayerExact(args[0]);
                if (p != null) p.sendMessage(ChatColor.GREEN + "¡Xavi te ha descongelado!");
            } else {
                frozenPlayers.add(target);
                sender.sendMessage(ChatColor.AQUA + "Has CONGELADO a " + args[0] + " (no puede moverse ni romper bloques).");
                Player p = Bukkit.getPlayerExact(args[0]);
                if (p != null) p.sendMessage(ChatColor.AQUA + "¡Xavi te ha CONGELADO!");
            }
            return true;
        }

        if (c.equals("god")) {
            Player target = (args.length >= 1) ? Bukkit.getPlayer(args[0]) : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Jugador no encontrado.");
                return true;
            }
            String key = target.getName().toLowerCase();
            if (godPlayers.contains(key)) {
                godPlayers.remove(key);
                target.sendMessage(ChatColor.YELLOW + "Modo Inmortal (God): " + ChatColor.RED + "DESACTIVADO");
                sender.sendMessage(ChatColor.YELLOW + "God Mode desactivado para " + target.getName());
            } else {
                godPlayers.add(key);
                target.setHealth(target.getMaxHealth());
                target.setFoodLevel(20);
                target.sendMessage(ChatColor.YELLOW + "Modo Inmortal (God): " + ChatColor.GREEN + "ACTIVADO");
                sender.sendMessage(ChatColor.GREEN + "God Mode activado para " + target.getName());
            }
            return true;
        }

        if (c.equals("fly")) {
            Player target = (args.length >= 1) ? Bukkit.getPlayer(args[0]) : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Jugador no encontrado.");
                return true;
            }
            boolean newFly = !target.getAllowFlight();
            target.setAllowFlight(newFly);
            if (newFly) target.setFlying(true);
            target.sendMessage(ChatColor.YELLOW + "Modo Vuelo: " + (newFly ? ChatColor.GREEN + "ACTIVADO" : ChatColor.RED + "DESACTIVADO"));
            sender.sendMessage(ChatColor.GREEN + "Vuelo cambiado a " + newFly + " para " + target.getName());
            return true;
        }

        if (c.equals("curar")) {
            Player target = (args.length >= 1) ? Bukkit.getPlayer(args[0]) : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Jugador no encontrado.");
                return true;
            }
            target.setHealth(target.getMaxHealth());
            target.setFoodLevel(20);
            target.setSaturation(20f);
            target.setFireTicks(0);
            target.sendMessage(ChatColor.GREEN + "¡Salud y comida restauradas al 100%!");
            sender.sendMessage(ChatColor.GREEN + "Curado " + target.getName());
            return true;
        }

        if (c.equals("pvp")) {
            World w = Bukkit.getWorld("world");
            if (w != null) {
                boolean newPvp = (args.length >= 1)
                        ? (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("si"))
                        : !w.getPVP();
                for (World world : Bukkit.getWorlds()) {
                    world.setPVP(newPvp);
                }
                Bukkit.broadcastMessage(ChatColor.GOLD + "[Servidor] Xavi ha cambiado el PvP a: "
                        + (newPvp ? ChatColor.RED + "ACTIVADO" : ChatColor.GREEN + "DESACTIVADO (Paz)"));
            }
            return true;
        }

        if (c.equals("regla")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /regla <explosion|fuego|construir|casa> <on|off>");
                return true;
            }
            String rule = args[0].toLowerCase();
            boolean val = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("si");
            if (rule.equals("explosion")) {
                antiExplosionDestroy = !val; // "explosion on" = explosions break blocks; "explosion off" = antiExplosionDestroy=true
                saveSettings();
                sender.sendMessage(ChatColor.GOLD + "Daño de explosiones a bloques: " + (val ? ChatColor.RED + "ACTIVADO" : ChatColor.GREEN + "BLOQUEADO (Seguro)"));
            } else if (rule.equals("fuego")) {
                antiFireSpread = !val;
                saveSettings();
                sender.sendMessage(ChatColor.GOLD + "Propagacion de fuego: " + (val ? ChatColor.RED + "ACTIVADO" : ChatColor.GREEN + "BLOQUEADO (Seguro)"));
            } else if (rule.equals("construir")) {
                globalBuildLock = !val;
                saveSettings();
                sender.sendMessage(ChatColor.GOLD + "Permiso global de construir para otros: " + (val ? ChatColor.GREEN + "PERMITIDO" : ChatColor.RED + "BLOQUEADO"));
            } else if (rule.equals("casa")) {
                houseProtectionEnabled = val;
                saveSettings();
                sender.sendMessage(ChatColor.GOLD + "Proteccion de la Casa de Xavi: " + (val ? ChatColor.GREEN + "ACTIVADA" : ChatColor.RED + "DESACTIVADA"));
            }
            return true;
        }

        if (c.equals("guardar")) {
            saveAllWorldsSilent();
            sender.sendMessage(ChatColor.GREEN + "[XaviAdminProtect] ¡Mundo e inventarios guardados en disco correctamente!");
            return true;
        }

        if (c.equals("recuperarinventario")) {
            Player xavi = Bukkit.getPlayerExact(ownerName);
            if (xavi == null && sender instanceof Player) xavi = (Player) sender;
            if (xavi != null) {
                restoreEpkInventory(xavi);
                sender.sendMessage(ChatColor.GREEN + "[XaviAdminProtect] ¡Inventario original del EPK (New World 8) restaurado para " + xavi.getName() + "!");
            } else {
                sender.sendMessage(ChatColor.RED + "Xavi debe estar conectado para restaurarle el inventario en vivo.");
            }
            return true;
        }

        if (c.equals("warn") || c.equals("advertir") || c.equals("advertencia")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /warn <jugador> [motivo]");
                return true;
            }
            String target = args[0];
            if (target.equalsIgnoreCase(ownerName)) {
                sender.sendMessage(ChatColor.RED + "¡No puedes ponerle advertencias a Xavi!");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            if (args.length > 1) {
                for (int i = 1; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
            } else {
                sb.append("Comportamiento inadecuado con otros jugadores");
            }
            String reason = sb.toString().trim();
            String key = target.toLowerCase();
            List<String> list = playerWarnings.get(key);
            if (list == null) {
                list = new ArrayList<String>();
                playerWarnings.put(key, list);
            }
            String timeStr = new SimpleDateFormat("HH:mm").format(new Date());
            list.add("[" + timeStr + "] " + reason);
            int count = list.size();
            saveWarnings();

            if (honorManager != null) {
                Player onlineTarget = Bukkit.getPlayerExact(target);
                if (onlineTarget != null) {
                    honorManager.addHonor(onlineTarget, -20, "Advertencia: " + reason);
                } else {
                    int h = honorManager.getHonor(target);
                    honorManager.setHonor(target, h - 20);
                }
            }

            if (count < 4) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[ADVERTENCIA] "
                        + ChatColor.YELLOW + target + ChatColor.RED + " ha recibido una advertencia (" + count + "/4)."
                        + ChatColor.GRAY + " Motivo: " + ChatColor.WHITE + reason);
                
                Player online = Bukkit.getPlayerExact(target);
                if (online != null) {
                    online.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "========================================");
                    online.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + " ¡HAS RECIBIDO UNA ADVERTENCIA! (" + count + "/4)");
                    online.sendMessage(ChatColor.YELLOW + " Motivo: " + ChatColor.WHITE + reason);
                    online.sendMessage(ChatColor.GRAY + " Advertencias acumuladas: " + ChatColor.YELLOW + count + " de 4.");
                    online.sendMessage(ChatColor.RED + " ¡A las 4 advertencias serás BANEADO del servidor!");
                    online.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "========================================");
                    try {
                        online.playSound(online.getLocation(), Sound.NOTE_BASS, 1.0f, 0.5f);
                    } catch (Exception ignored) {}
                }
                sender.sendMessage(ChatColor.GREEN + "Advertencia aplicada a " + target + " (" + count + "/4).");
            } else {
                String banReason = "Acumular 4 advertencias. Última: " + reason;
                Bukkit.getBanList(BanList.Type.NAME).addBan(target, banReason, null, sender.getName());
                
                Player online = Bukkit.getPlayerExact(target);
                if (online != null) {
                    if (online.getAddress() != null) {
                        String ip = online.getAddress().getAddress().getHostAddress();
                        Bukkit.getBanList(BanList.Type.IP).addBan(ip, banReason, null, sender.getName());
                    }
                    online.kickPlayer(ChatColor.DARK_RED + "" + ChatColor.BOLD + "¡HAS SIDO BANEADO DEL SERVIDOR!\n\n"
                            + ChatColor.RED + "Has acumulado " + count + " de 4 advertencias.\n"
                            + ChatColor.YELLOW + "Último motivo: " + ChatColor.WHITE + reason + "\n\n"
                            + ChatColor.GRAY + "Respeta las reglas y a los demás jugadores.");
                }

                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:ban " + target + " " + banReason);
                } catch (Exception ignored) {}

                Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[BAN AUTOMÁTICO] "
                        + ChatColor.YELLOW + target + ChatColor.RED + " ha alcanzado 4 advertencias y ha sido BANEADO del servidor!");
                sender.sendMessage(ChatColor.DARK_RED + "El jugador " + target + " ha alcanzado 4 advertencias y ha sido BANEADO automáticamente.");
            }
            return true;
        }

        if (c.equals("warns") || c.equals("advertencias") || c.equals("verwarns")) {
            if (args.length < 1) {
                boolean any = false;
                for (Map.Entry<String, List<String>> entry : playerWarnings.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    sender.sendMessage(ChatColor.GREEN + "No hay ningún jugador con advertencias activas (todos limpios).");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Jugadores con Advertencias ===");
                for (Map.Entry<String, List<String>> entry : playerWarnings.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        sender.sendMessage(ChatColor.YELLOW + "- " + entry.getKey() + ": " + ChatColor.RED + entry.getValue().size() + "/4 advertencias");
                    }
                }
                sender.sendMessage(ChatColor.GRAY + "Usa /warns <jugador> para ver el historial y motivos.");
                return true;
            }
            String target = args[0].toLowerCase();
            List<String> list = playerWarnings.get(target);
            if (list == null || list.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "El jugador " + args[0] + " no tiene ninguna advertencia (0/4).");
                return true;
            }
            sender.sendMessage(ChatColor.GOLD + "=== Advertencias de " + args[0] + " (" + list.size() + "/4) ===");
            for (int i = 0; i < list.size(); i++) {
                sender.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + ChatColor.WHITE + list.get(i));
            }
            return true;
        }

        if (c.equals("unwarn") || c.equals("quitarwarn") || c.equals("delwarn")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /unwarn <jugador>");
                return true;
            }
            String target = args[0].toLowerCase();
            List<String> list = playerWarnings.get(target);
            if (list == null || list.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "El jugador " + args[0] + " no tiene advertencias para quitar (0/4).");
                return true;
            }
            list.remove(list.size() - 1);
            int newCount = list.size();
            saveWarnings();
            sender.sendMessage(ChatColor.GREEN + "Se ha retirado una advertencia a " + args[0] + ". Ahora tiene: " + newCount + "/4.");
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) {
                online.sendMessage(ChatColor.GREEN + "[Servidor] Xavi te ha retirado una advertencia. Ahora tienes: " + newCount + "/4.");
            }
            return true;
        }

        if (c.equals("clearwarns") || c.equals("limpiarwarns")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /clearwarns <jugador>");
                return true;
            }
            String target = args[0].toLowerCase();
            playerWarnings.remove(target);
            saveWarnings();
            sender.sendMessage(ChatColor.GREEN + "Se han borrado todas las advertencias de " + args[0] + " (0/4).");
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) {
                online.sendMessage(ChatColor.GREEN + "[Servidor] Xavi ha limpiado todas tus advertencias (0/4).");
            }
            return true;
        }

        if (c.equals("autoapagado") || c.equals("autoshutdown") || c.equals("apagarvacio")) {
            if (args.length < 1) {
                if (idleLimitMinutes <= 0) {
                    sender.sendMessage(ChatColor.GOLD + "[Auto-Apagado] Actualmente " + ChatColor.RED + "DESACTIVADO" + ChatColor.GOLD + ".");
                } else {
                    int remaining = (idleLimitMinutes * 60) - idleSeconds;
                    if (remaining < 0) remaining = 0;
                    sender.sendMessage(ChatColor.GOLD + "[Auto-Apagado] " + ChatColor.GREEN + "ACTIVADO"
                            + ChatColor.GOLD + " a los " + ChatColor.YELLOW + idleLimitMinutes + " minutos"
                            + ChatColor.GOLD + " sin jugadores.");
                    if (Bukkit.getOnlinePlayers().isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "Tiempo vacio: " + idleSeconds + "s. Se apagara en " + remaining + "s.");
                    } else {
                        sender.sendMessage(ChatColor.GRAY + "Hay jugadores conectados, el temporizador esta pausado.");
                    }
                }
                sender.sendMessage(ChatColor.YELLOW + "Uso: /autoapagado <minutos|on|off>");
                return true;
            }
            if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("false") || args[0].equalsIgnoreCase("no")) {
                idleLimitMinutes = 0;
                idleSeconds = 0;
                saveSettings();
                sender.sendMessage(ChatColor.RED + "[Auto-Apagado] Desactivado. El servidor permanecera encendido indefinidamente.");
                return true;
            }
            if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("si")) {
                idleLimitMinutes = 10;
                idleSeconds = 0;
                saveSettings();
                sender.sendMessage(ChatColor.GREEN + "[Auto-Apagado] Activado a 10 minutos de inactividad.");
                return true;
            }
            try {
                int mins = Integer.parseInt(args[0]);
                if (mins < 1) {
                    sender.sendMessage(ChatColor.RED + "Los minutos deben ser al menos 1 (o usa /autoapagado off).");
                    return true;
                }
                idleLimitMinutes = mins;
                idleSeconds = 0;
                saveSettings();
                sender.sendMessage(ChatColor.GREEN + "[Auto-Apagado] Fijado a " + ChatColor.YELLOW + mins + " minutos" + ChatColor.GREEN + " sin jugadores.");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Formato invalido. Usa un numero de minutos (ej. /autoapagado 15) o 'off'.");
            }
            return true;
        }


        // ================= ATAJOS DIRECTOS DE ALIANZA =================
        if (c.equals("unirse") || c.equals("unirme") || c.equals("join") || c.equals("entrar") || c.equals("aceptar")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores en el juego pueden unirse a una alianza.");
                return true;
            }
            String clanName = args.length > 0 ? args[0] : null;
            allianceManager.joinAlliance((Player) sender, clanName);
            return true;
        }

        if (c.equals("crearclan") || c.equals("crearalianza") || c.equals("clancrear") || c.equals("fundarclan")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores en el juego pueden crear una alianza.");
                return true;
            }
            String clanName = args.length > 0 ? args[0] : null;
            allianceManager.createAlliance((Player) sender, clanName);
            return true;
        }

        if (c.equals("invitar") || c.equals("invite") || c.equals("claninvitar")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores en el juego.");
                return true;
            }
            String target = args.length > 0 ? args[0] : null;
            allianceManager.invite((Player) sender, target);
            return true;
        }

        if (c.equals("salirclan") || c.equals("saliralianza") || c.equals("dejarclan")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores en el juego.");
                return true;
            }
            allianceManager.leave((Player) sender);
            return true;
        }

        // ================= ALIANZAS =================
        if (c.equals("alianza") || c.equals("alliance") || c.equals("clan") || c.equals("faccion")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.GOLD + "========================================");
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== SISTEMA DE ALIANZAS Y CLANES ===");
                sender.sendMessage(ChatColor.YELLOW + "/crearclan <nombre> " + ChatColor.GRAY + "- Funda una nueva alianza");
                sender.sendMessage(ChatColor.YELLOW + "/unirse [nombre] " + ChatColor.GRAY + "- Únete a un clan o acepta invitación");
                sender.sendMessage(ChatColor.YELLOW + "/invitar <jugador> " + ChatColor.GRAY + "- Invita a un amigo a tu clan");
                sender.sendMessage(ChatColor.YELLOW + "/salirclan " + ChatColor.GRAY + "- Abandona tu alianza actual");
                sender.sendMessage(ChatColor.YELLOW + "/alianza abierta " + ChatColor.GRAY + "- Alterna si tu clan es abierto o privado (Líder)");
                sender.sendMessage(ChatColor.YELLOW + "/alianza expulsar <jugador> " + ChatColor.GRAY + "- Expulsa a un miembro (Líder)");
                sender.sendMessage(ChatColor.YELLOW + "/alianza fuego " + ChatColor.GRAY + "- Activa/desactiva fuego amigo (Líder)");
                sender.sendMessage(ChatColor.YELLOW + "/alianza guerra <alianza> " + ChatColor.GRAY + "- Declara la guerra a otra alianza");
                sender.sendMessage(ChatColor.YELLOW + "/alianza paz <alianza> " + ChatColor.GRAY + "- Firma la paz con una alianza rival");
                sender.sendMessage(ChatColor.YELLOW + "/alianza info [alianza] " + ChatColor.GRAY + "- Muestra detalles de una alianza");
                sender.sendMessage(ChatColor.YELLOW + "/alianza lista " + ChatColor.GRAY + "- Muestra todas las alianzas activas");
                sender.sendMessage(ChatColor.YELLOW + "/ac <mensaje> " + ChatColor.GRAY + "- Chat privado de tu alianza");
                sender.sendMessage(ChatColor.GOLD + "========================================");
                return true;
            }

            String sub = args[0].toLowerCase();
            if (sub.equals("crear")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores en el juego pueden crear alianzas.");
                    return true;
                }
                String clanName = args.length > 1 ? args[1] : null;
                allianceManager.createAlliance((Player) sender, clanName);
                return true;
            }

            if (sub.equals("invitar")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                String target = args.length > 1 ? args[1] : null;
                allianceManager.invite((Player) sender, target);
                return true;
            }

            if (sub.equals("aceptar") || sub.equals("unirse") || sub.equals("join") || sub.equals("entrar")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                String alName = args.length > 1 ? args[1] : null;
                allianceManager.joinAlliance((Player) sender, alName);
                return true;
            }

            if (sub.equals("abierta") || sub.equals("abrir") || sub.equals("cerrar") || sub.equals("publica") || sub.equals("privada")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                allianceManager.toggleOpenJoin((Player) sender);
                return true;
            }

            if (sub.equals("salir") || sub.equals("leave")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                allianceManager.leave((Player) sender);
                return true;
            }

            if (sub.equals("expulsar") || sub.equals("kick")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /alianza expulsar <jugador>");
                    return true;
                }
                allianceManager.kick((Player) sender, args[1]);
                return true;
            }

            if (sub.equals("fuego") || sub.equals("friendlyfire") || sub.equals("ff")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                allianceManager.toggleFriendlyFire((Player) sender);
                return true;
            }

            if (sub.equals("guerra") || sub.equals("war")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /alianza guerra <alianza>");
                    return true;
                }
                allianceManager.declareWar((Player) sender, args[1]);
                return true;
            }

            if (sub.equals("paz") || sub.equals("peace")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /alianza paz <alianza>");
                    return true;
                }
                allianceManager.makePeace((Player) sender, args[1]);
                return true;
            }

            if (sub.equals("chat")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Solo jugadores.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /alianza chat <mensaje> (o usa /ac <mensaje>)");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                allianceManager.chat((Player) sender, sb.toString().trim());
                return true;
            }

            if (sub.equals("lista") || sub.equals("list")) {
                List<AllianceManager.Alliance> list = allianceManager.getAllAlliances();
                if (list.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No hay ninguna alianza creada en el servidor. ¡Crea una con /alianza crear <nombre>!");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Alianzas del Servidor (" + list.size() + ") ===");
                for (AllianceManager.Alliance al : list) {
                    sender.sendMessage(ChatColor.AQUA + "- [" + al.name + "] " + ChatColor.YELLOW + "Líder: " + ChatColor.WHITE + al.leader
                            + ChatColor.GRAY + " | Miembros: " + ChatColor.GREEN + al.members.size()
                            + ChatColor.GRAY + " | Bajas: " + ChatColor.RED + al.kills);
                }
                return true;
            }

            if (sub.equals("info")) {
                String targetAlName = args.length > 1 ? args[1] : null;
                AllianceManager.Alliance al;
                if (targetAlName != null) {
                    al = allianceManager.getAllianceByName(targetAlName);
                } else if (sender instanceof Player) {
                    al = allianceManager.getAlliance(sender.getName());
                } else {
                    sender.sendMessage(ChatColor.RED + "Especifica una alianza: /alianza info <nombre>");
                    return true;
                }

                if (al == null) {
                    sender.sendMessage(ChatColor.RED + "Alianza no encontrada.");
                    return true;
                }

                sender.sendMessage(ChatColor.GOLD + "========================================");
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Alianza: [" + al.name + "]");
                sender.sendMessage(ChatColor.YELLOW + "Líder: " + ChatColor.GOLD + al.leader);
                sender.sendMessage(ChatColor.YELLOW + "Miembros (" + al.members.size() + "): " + ChatColor.WHITE + String.join(", ", al.members));
                sender.sendMessage(ChatColor.YELLOW + "Fuego amigo: " + (al.friendlyFire ? ChatColor.RED + "ACTIVADO" : ChatColor.GREEN + "DESACTIVADO (Protegidos)"));
                sender.sendMessage(ChatColor.YELLOW + "Enemigos / En Guerra: " + (al.enemies.isEmpty() ? ChatColor.GREEN + "Ninguno (Paz)" : ChatColor.RED + String.join(", ", al.enemies)));
                sender.sendMessage(ChatColor.YELLOW + "Bajas PvP acumuladas: " + ChatColor.RED + al.kills);
                sender.sendMessage(ChatColor.GOLD + "========================================");
                return true;
            }

            sender.sendMessage(ChatColor.RED + "Subcomando desconocido. Escribe /alianza para ver la ayuda.");
            return true;
        }

        // ================= CHAT DE ALIANZA =================
        if (c.equals("ac") || c.equals("alliancechat") || c.equals("chatclan")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores en el juego.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /ac <mensaje>");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (String arg : args) {
                sb.append(arg).append(" ");
            }
            allianceManager.chat((Player) sender, sb.toString().trim());
            return true;
        }

        // ================= HONOR (ESTILO RDR2) =================
        if (c.equals("honor") || c.equals("mihonor") || c.equals("reputacion")) {
            String target = (args.length >= 1) ? args[0] : sender.getName();
            int honor = honorManager.getHonor(target);
            String title = honorManager.getHonorTitle(honor);
            String bar = honorManager.getHonorBar(honor);

            sender.sendMessage(ChatColor.GOLD + "========================================");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== 🤠 SISTEMA DE HONOR (ESTILO RDR2) ===");
            sender.sendMessage(ChatColor.WHITE + "Jugador: " + ChatColor.YELLOW + target);
            sender.sendMessage(ChatColor.WHITE + "Estatus: " + title);
            sender.sendMessage(ChatColor.WHITE + "Puntuación de Honor: " + (honor > 0 ? ChatColor.GREEN + "+" + honor : (honor < 0 ? ChatColor.RED + "" + honor : ChatColor.GRAY + "0")));
            sender.sendMessage(bar);
            sender.sendMessage("");
            if (honor >= 40) {
                sender.sendMessage(ChatColor.YELLOW + "✨ Ventaja: Considerado un héroe honorable del reino.");
            } else if (honor <= -40) {
                sender.sendMessage(ChatColor.DARK_RED + "☠️ Forajido Buscado: ¡Otros jugadores recibirán +15 de honor si te cazan!");
            } else {
                sender.sendMessage(ChatColor.GRAY + "⚖️ Neutral: Tus actos definirán si serás un héroe o un forajido.");
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "----------------------------------------");
            sender.sendMessage(ChatColor.GREEN + "▲ Subir Honor: " + ChatColor.GRAY + "Cazar monstruos (+2), donar ítems (/donar) (+10), cazar forajidos (+15)");
            sender.sendMessage(ChatColor.RED + "▼ Bajar Honor: " + ChatColor.GRAY + "Matar inocentes (-25), grifear (-50), recibir advertencias /warn (-20)");
            sender.sendMessage(ChatColor.GOLD + "========================================");
            return true;
        }

        // ================= DONAR ITEMS =================
        if (c.equals("donar") || c.equals("giveitem") || c.equals("regalar")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Solo jugadores.");
                return true;
            }
            Player player = (Player) sender;
            if (args.length < 1) {
                sender.sendMessage(ChatColor.YELLOW + "Uso: /donar <jugador>");
                sender.sendMessage(ChatColor.GRAY + "Regalas el objeto que sostienes en la mano y ganas +10 de Honor.");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "El jugador " + args[0] + " no está conectado.");
                return true;
            }
            if (target.getName().equalsIgnoreCase(player.getName())) {
                sender.sendMessage(ChatColor.RED + "No puedes donarte objetos a ti mismo.");
                return true;
            }
            ItemStack inHand = player.getItemInHand();
            if (inHand == null || inHand.getType() == Material.AIR) {
                sender.sendMessage(ChatColor.RED + "¡Debes sostener en la mano el objeto que deseas donar!");
                return true;
            }

            String matName = inHand.getType().name();
            int amount = inHand.getAmount();

            // Transfer item
            target.getInventory().addItem(inHand);
            player.setItemInHand(null);

            // Audio & Honor reward
            honorManager.addHonor(player, 10, "Donación generosa de " + amount + "x " + matName + " a " + target.getName());

            try {
                target.playSound(target.getLocation(), Sound.LEVEL_UP, 1.0f, 1.5f);
            } catch (Exception ignored) {}

            target.sendMessage(ChatColor.GREEN + "🎁 ¡" + player.getName() + " te ha donado " + amount + "x " + matName + "!");
            player.sendMessage(ChatColor.GREEN + "¡Has donado con éxito " + amount + "x " + matName + " a " + target.getName() + " (+10 Honor)!");
            return true;
        }

        // ================= FORAJIDOS Y HÉROES =================
        if (c.equals("forajidos") || c.equals("topforajidos") || c.equals("outlaws") || c.equals("criminales")) {
            sender.sendMessage(ChatColor.DARK_RED + "========================================");
            sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "=== ☠️ FORAJIDOS BUSCADOS DEL SERVIDOR ===");
            sender.sendMessage(ChatColor.GRAY + "¡Abatir a estos criminales otorga +15 de Honor y botín!");
            boolean found = false;
            for (Map.Entry<String, Integer> entry : honorManager.getAllHonorScores().entrySet()) {
                if (entry.getValue() <= -40) {
                    found = true;
                    sender.sendMessage(ChatColor.RED + "- " + entry.getKey() + ": " + ChatColor.YELLOW + entry.getValue() + " honor "
                            + honorManager.getHonorBadge(entry.getValue()));
                }
            }
            if (!found) {
                sender.sendMessage(ChatColor.GREEN + "No hay ningún forajido peligroso buscado en este momento.");
            }
            sender.sendMessage(ChatColor.DARK_RED + "========================================");
            return true;
        }

        if (c.equals("heroes") || c.equals("topheroes")) {
            sender.sendMessage(ChatColor.GOLD + "========================================");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== 🌟 HÉROES LEGENDARIOS DEL REINO ===");
            boolean found = false;
            for (Map.Entry<String, Integer> entry : honorManager.getAllHonorScores().entrySet()) {
                if (entry.getValue() >= 40) {
                    found = true;
                    sender.sendMessage(ChatColor.YELLOW + "- " + entry.getKey() + ": " + ChatColor.GREEN + "+" + entry.getValue() + " honor "
                            + honorManager.getHonorBadge(entry.getValue()));
                }
            }
            if (!found) {
                sender.sendMessage(ChatColor.GRAY + "Aún no hay ningún héroe con honor alto (+40). ¡Haz buenas acciones para ser el primero!");
            }
            sender.sendMessage(ChatColor.GOLD + "========================================");
            return true;
        }

        // ================= GESTION DE COFRES PRIVADOS =================
        if (c.equals("duenocofre") || c.equals("setduenocofre") || c.equals("propietariocofre") || c.equals("cambiarduenocofre") || c.equals("setcofrer") || c.equals("cofrerdueno")) {
            if (chestManager == null) {
                sender.sendMessage(ChatColor.RED + "El gestor de cofres no está inicializado.");
                return true;
            }

            // Caso 1: Por coordenadas -> /duenocofre <x> <y> <z> <jugador> [mundo]
            if (args.length >= 4) {
                try {
                    int x = Integer.parseInt(args[0]);
                    int y = Integer.parseInt(args[1]);
                    int z = Integer.parseInt(args[2]);
                    String newOwner = args[3];
                    String wName = args.length >= 5 ? args[4] : (sender instanceof Player ? ((Player) sender).getWorld().getName() : "world");
                    World w = Bukkit.getWorld(wName);
                    if (w == null) {
                        sender.sendMessage(ChatColor.RED + "El mundo '" + wName + "' no existe.");
                        return true;
                    }
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) {
                        sender.sendMessage(ChatColor.RED + "En [" + x + ", " + y + ", " + z + "] en " + wName + " no hay ningún cofre (hay: " + b.getType() + ").");
                        return true;
                    }
                    chestManager.claimChest(b, newOwner);
                    sender.sendMessage(ChatColor.GREEN + "[Cofres] ¡Dueño actualizado con éxito!");
                    sender.sendMessage(ChatColor.YELLOW + "Cofre en [" + x + ", " + y + ", " + z + "] asignado a: " + ChatColor.GOLD + newOwner);
                    return true;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Las coordenadas deben ser números enteros. Uso: /duenocofre <x> <y> <z> <jugador> [mundo]");
                    return true;
                }
            }

            // Caso 2: Mirando al cofre -> /duenocofre <jugador>
            if (args.length == 1) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Desde la consola debes indicar coordenadas: /duenocofre <x> <y> <z> <jugador> [mundo]");
                    return true;
                }
                Player p = (Player) sender;
                Block b = getTargetChestBlock(p, 6);
                if (b == null || (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST)) {
                    sender.sendMessage(ChatColor.RED + "Debes mirar fijamente a un cofre a menos de 6 bloques.");
                    return true;
                }
                String newOwner = args[0];
                chestManager.claimChest(b, newOwner);
                sender.sendMessage(ChatColor.GREEN + "[Cofres] ¡Dueño actualizado con éxito!");
                sender.sendMessage(ChatColor.YELLOW + "El cofre en [" + b.getX() + ", " + b.getY() + ", " + b.getZ() + "] ahora pertenece a: " + ChatColor.GOLD + newOwner);
                return true;
            }

            sender.sendMessage(ChatColor.YELLOW + "Uso mirando al cofre: " + ChatColor.WHITE + "/duenocofre <jugador>");
            sender.sendMessage(ChatColor.YELLOW + "Uso por coordenadas: " + ChatColor.WHITE + "/duenocofre <x> <y> <z> <jugador> [mundo]");
            return true;
        }

        if (c.equals("liberarcofre") || c.equals("unclaimcofre") || c.equals("desreclamarcofre")) {
            if (chestManager == null) {
                sender.sendMessage(ChatColor.RED + "El gestor de cofres no está inicializado.");
                return true;
            }

            // Por coordenadas: /liberarcofre <x> <y> <z> [mundo]
            if (args.length >= 3) {
                try {
                    int x = Integer.parseInt(args[0]);
                    int y = Integer.parseInt(args[1]);
                    int z = Integer.parseInt(args[2]);
                    String wName = args.length >= 4 ? args[3] : (sender instanceof Player ? ((Player) sender).getWorld().getName() : "world");
                    World w = Bukkit.getWorld(wName);
                    if (w == null) {
                        sender.sendMessage(ChatColor.RED + "El mundo '" + wName + "' no existe.");
                        return true;
                    }
                    Block b = w.getBlockAt(x, y, z);
                    chestManager.removeChest(b);
                    sender.sendMessage(ChatColor.GREEN + "[Cofres] El cofre en [" + x + ", " + y + ", " + z + "] ha sido liberado (sin dueño).");
                    return true;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Coordenadas inválidas. Uso: /liberarcofre <x> <y> <z> [mundo]");
                    return true;
                }
            }

            // Mirando al cofre: /liberarcofre
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Desde la consola usa: /liberarcofre <x> <y> <z> [mundo]");
                return true;
            }
            Player p = (Player) sender;
            Block b = getTargetChestBlock(p, 6);
            if (b == null || (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST)) {
                sender.sendMessage(ChatColor.RED + "Debes mirar fijamente a un cofre a menos de 6 bloques.");
                return true;
            }
            chestManager.removeChest(b);
            sender.sendMessage(ChatColor.GREEN + "[Cofres] Cofre liberado. Ahora cualquiera puede reclamarlo al abrirlo.");
            return true;
        }

        if (c.equals("infocofre") || c.equals("vercofre") || c.equals("cofreinfo")) {
            if (chestManager == null) {
                sender.sendMessage(ChatColor.RED + "El gestor de cofres no está inicializado.");
                return true;
            }

            Block b = null;
            if (args.length >= 3) {
                try {
                    int x = Integer.parseInt(args[0]);
                    int y = Integer.parseInt(args[1]);
                    int z = Integer.parseInt(args[2]);
                    String wName = args.length >= 4 ? args[3] : (sender instanceof Player ? ((Player) sender).getWorld().getName() : "world");
                    World w = Bukkit.getWorld(wName);
                    if (w != null) b = w.getBlockAt(x, y, z);
                } catch (Exception ignored) {}
            } else if (sender instanceof Player) {
                b = getTargetChestBlock((Player) sender, 6);
            }

            if (b == null || (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST)) {
                sender.sendMessage(ChatColor.RED + "Debes mirar a un cofre o indicar coordenadas: /infocofre <x> <y> <z> [mundo]");
                return true;
            }

            String owner = chestManager.getChestOwner(b);
            ChestManager.ChestData data = chestManager.getChestData(b);
            boolean isDouble = chestManager.getDoubleChestNeighbor(b) != null;

            sender.sendMessage(ChatColor.GOLD + "========== [INFORMACION DE COFRE] ==========");
            sender.sendMessage(ChatColor.YELLOW + "Tipo: " + ChatColor.WHITE + (isDouble ? "Cofre Doble" : "Cofre Simple") + " (" + b.getType().name() + ")");
            sender.sendMessage(ChatColor.YELLOW + "Ubicación: " + ChatColor.WHITE + "[" + b.getX() + ", " + b.getY() + ", " + b.getZ() + "] (" + b.getWorld().getName() + ")");
            if (owner != null) {
                sender.sendMessage(ChatColor.YELLOW + "Dueño: " + ChatColor.GOLD + owner);
                if (data != null && data.claimedDate != null && !data.claimedDate.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Reclamado el: " + data.claimedDate);
                }
            } else {
                sender.sendMessage(ChatColor.GREEN + "Estado: " + ChatColor.AQUA + "LIBRE (Sin reclamar)");
                sender.sendMessage(ChatColor.GRAY + "El primer jugador que lo abra será su dueño.");
            }
            sender.sendMessage(ChatColor.GOLD + "===========================================");
            return true;
        }

        if (c.equals("listacofres") || c.equals("vercofres") || c.equals("claimedchests")) {
            if (chestManager == null) {
                sender.sendMessage(ChatColor.RED + "El gestor de cofres no está inicializado.");
                return true;
            }
            Map<String, ChestManager.ChestData> all = chestManager.getAllChests();
            sender.sendMessage(ChatColor.GOLD + "========== [COFRES RECLAMADOS (" + all.size() + " bloques)] ==========");
            if (all.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "Aún no hay ningún cofre reclamado en el servidor.");
            } else {
                Map<String, Integer> perPlayer = new LinkedHashMap<String, Integer>();
                for (ChestManager.ChestData d : all.values()) {
                    String ow = d.owner.toLowerCase();
                    perPlayer.put(ow, perPlayer.containsKey(ow) ? perPlayer.get(ow) + 1 : 1);
                }
                for (Map.Entry<String, Integer> e : perPlayer.entrySet()) {
                    sender.sendMessage(ChatColor.YELLOW + "- " + ChatColor.GOLD + e.getKey() + ChatColor.WHITE + ": " + e.getValue() + " bloques de cofre");
                }
            }
            sender.sendMessage(ChatColor.GOLD + "=================================================");
            return true;
        }

        // ================= SISTEMA DE NERFEO (PROTENGAVILA / OTROS) =================
        if (c.equals("nerf") || c.equals("nerfeo") || c.equals("setnerf") || c.equals("nerfear")) {
            if (nerfManager == null) {
                sender.sendMessage(ChatColor.RED + "El gestor de nerfeo no está inicializado.");
                return true;
            }
            if (args.length == 0) {
                Map<String, Boolean> all = nerfManager.getAllNerfed();
                sender.sendMessage(ChatColor.GOLD + "========== [ESTADO DE JUGADORES NERFEADOS] ==========");
                for (Map.Entry<String, Boolean> e : all.entrySet()) {
                    sender.sendMessage(ChatColor.YELLOW + "- " + ChatColor.WHITE + e.getKey() + ": "
                            + (e.getValue() ? ChatColor.RED + "NERFEADO (50% Vida, 50% Inv, Daño -40%, XP -50%, Minería Lenta)" : ChatColor.GREEN + "NORMAL (Sin restricciones)"));
                }
                sender.sendMessage(ChatColor.GRAY + "Usa: /nerf <jugador> [on|off]");
                sender.sendMessage(ChatColor.GOLD + "=====================================================");
                return true;
            }

            String target = args[0].toLowerCase();
            boolean newState;
            if (args.length >= 2) {
                newState = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("si") || args[1].equalsIgnoreCase("activar");
            } else {
                newState = !nerfManager.isNerfed(target);
            }

            nerfManager.setNerfed(target, newState);
            sender.sendMessage(ChatColor.GOLD + "[Nerf] Jugador " + ChatColor.YELLOW + target + ChatColor.GOLD
                    + ": " + (newState ? ChatColor.RED + "NERFEADO (50% vida, 50% inventario, daño -40%, XP -50%, minería lenta)" : ChatColor.GREEN + "MODO NORMAL (Sin restricciones)"));
            return true;
        }

        return false;
    }
}
