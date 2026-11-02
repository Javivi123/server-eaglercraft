package com.xavi.protect;

import net.minecraft.server.v1_8_R3.EntityCreature;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftCreature;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gestor del Cuerpo de Policía Nacional IA.
 * Coordina una dotación de 5 agentes autónomos especializados:
 * 1. Agente Torres  -> Comisario Jefe (Plaza Central y Superficie Urbana)
 * 2. Agente Ramírez -> Inspector de Minas (Minas Profundas Diamantes y Búnker Subterráneo)
 * 3. Agente Morales -> Guardia Residencial (Viviendas y Casas Privadas de Jugadores)
 * 4. Agente Vega    -> Vigilante Espeleológico (Cuevas Exploradas con Antorchas)
 * 5. Agente Navarro -> Unidad de Intervención Rápida (Vigilancia Dinámica donde haya Jugadores)
 */
public class PoliceManager {

    public static class PatrolSector {
        public String id;
        public String name;
        public String world;
        public boolean isUnderground;
        public List<Location> waypoints = new ArrayList<Location>();

        public PatrolSector(String id, String name, String world, boolean isUnderground) {
            this.id = id;
            this.name = name;
            this.world = world;
            this.isUnderground = isUnderground;
        }

        public void addWaypoint(Location loc) {
            if (loc == null) return;
            for (Location existing : waypoints) {
                if (existing.distanceSquared(loc) < 3.0 * 3.0) return;
            }
            waypoints.add(loc);
        }
    }

    public static class PoliceOfficer {
        public String id;
        public String name;
        public String rank;
        public String customName;
        public String assignment;
        public Color armorColor;
        public Material helmetMaterial;
        public String defaultSectorId;
        public LivingEntity entity;
        public boolean killedThisSession = false;
        public PatrolSector activeSector;
        public int waypointIndex = 0;
        public int ticksInCurrentSector = 0;
        public int suspicionCooldown = 0;

        public PoliceOfficer(String id, String name, String rank, String assignment, Color armorColor, Material helmetMaterial, String defaultSectorId) {
            this.id = id;
            this.name = name;
            this.rank = rank;
            this.customName = ChatColor.BLUE + "" + ChatColor.BOLD + "🚔 [POLICÍA] " + name;
            this.assignment = assignment;
            this.armorColor = armorColor;
            this.helmetMaterial = helmetMaterial;
            this.defaultSectorId = defaultSectorId;
        }

        public boolean isAlive() {
            return entity != null && entity.isValid() && !entity.isDead();
        }

        public boolean canSee(Location targetLoc) {
            if (!isAlive()) return false;
            Location eye = entity.getEyeLocation();
            if (!eye.getWorld().equals(targetLoc.getWorld())) return false;

            double distSq = eye.distanceSquared(targetLoc);
            if (distSq > 28.0 * 28.0) return false;

            // Cono de visión frontal (~150° horizontales)
            Vector policeDir = eye.getDirection().setY(0).normalize();
            Vector toTarget = targetLoc.clone().subtract(eye).toVector().setY(0).normalize();
            double dot = policeDir.dot(toTarget);
            if (dot < 0.22) {
                return false; // Está a su espalda o fuera de su campo visual
            }

            // Raycast de comprobación de muros opacos
            Vector ray = targetLoc.toVector().subtract(eye.toVector());
            double length = ray.length();
            if (length < 1.0) return true;

            Vector step = ray.clone().normalize().multiply(0.45);
            Location cur = eye.clone();
            int steps = (int) (length / 0.45);

            for (int i = 0; i < steps - 1; i++) {
                cur.add(step);
                Block b = cur.getBlock();
                if (b != null && isVisionBlocking(b.getType())) {
                    return false; // Muro u obstáculo opaco
                }
            }
            return true;
        }

        private static boolean isVisionBlocking(Material m) {
            if (m == null || m == Material.AIR) return false;
            if (m == Material.GLASS || m == Material.THIN_GLASS || m == Material.STAINED_GLASS || m == Material.STAINED_GLASS_PANE) return false;
            if (m == Material.FENCE || m == Material.FENCE_GATE || m == Material.IRON_FENCE || m == Material.NETHER_FENCE) return false;
            if (m == Material.TORCH || m == Material.WATER || m == Material.STATIONARY_WATER) return false;
            if (m == Material.SIGN_POST || m == Material.WALL_SIGN || m == Material.CARPET || m == Material.LADDER) return false;
            return m.isOccluding() || m.isSolid();
        }
    }

    private final XaviAdminProtect plugin;
    private final Map<String, PoliceOfficer> officers = new LinkedHashMap<String, PoliceOfficer>();
    private final Map<String, PatrolSector> sectors = new LinkedHashMap<String, PatrolSector>();
    private int patrolTaskId = -1;
    private final Random random = new Random();

    public PoliceManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        initOfficers();
        rebuildPatrolSectors();
        startPatrolLoop();
    }

    private void initOfficers() {
        officers.clear();
        // 1. Agente Torres - Comisario Jefe (Plaza Central y Casas Principales)
        officers.put("torres", new PoliceOfficer(
                "torres", "Agente Torres", "Comisario Jefe",
                "Plaza Central y Casas Principales [Superficie]",
                Color.fromRGB(16, 44, 87), Material.CHAINMAIL_HELMET, "central"));

        // 2. Agente Ramírez - Inspector de Minas (Subterráneo, Diamantes y Búnker)
        officers.put("ramirez", new PoliceOfficer(
                "ramirez", "Agente Ramírez", "Inspector de Minas",
                "Minas Profundas Diamantes y Búnker [Subterráneo]",
                Color.fromRGB(25, 35, 60), Material.IRON_HELMET, "sub_minas_diamantes"));

        // 3. Agente Morales - Guardia Residencial (Casas y Viviendas)
        officers.put("morales", new PoliceOfficer(
                "morales", "Agente Morales", "Guardia Residencial",
                "Barrios Residenciales y Casas de Jugadores",
                Color.fromRGB(20, 60, 120), Material.LEATHER_HELMET, "casas_residencial"));

        // 4. Agente Vega - Vigilante Espeleológico (Cuevas Exploradas con Antorchas)
        officers.put("vega", new PoliceOfficer(
                "vega", "Agente Vega", "Vigilante de Cuevas",
                "Cuevas Exploradas con Antorchas [Subterráneo]",
                Color.fromRGB(30, 50, 75), Material.CHAINMAIL_HELMET, "cuevas_antorchas"));

        // 5. Agente Navarro - Unidad de Intervención Rápida (Zonas Activas con Jugadores)
        officers.put("navarro", new PoliceOfficer(
                "navarro", "Agente Navarro", "Unidad Rápida",
                "Vigilancia Dinámica donde haya Jugadores Activos",
                Color.fromRGB(10, 80, 140), Material.IRON_HELMET, "dinamico_jugadores"));
    }

    public Map<String, PoliceOfficer> getOfficers() {
        return officers;
    }

    public PoliceOfficer getOfficer(String id) {
        if (id == null) return null;
        id = id.toLowerCase();
        if (officers.containsKey(id)) return officers.get(id);
        for (PoliceOfficer off : officers.values()) {
            if (off.name.toLowerCase().contains(id) || off.id.equalsIgnoreCase(id)) return off;
        }
        return null;
    }

    public boolean isKilledThisSession() {
        for (PoliceOfficer off : officers.values()) {
            if (!off.killedThisSession) return false;
        }
        return true;
    }

    public void setKilledThisSession(boolean killed) {
        for (PoliceOfficer off : officers.values()) {
            off.killedThisSession = killed;
        }
    }

    public LivingEntity getPoliceEntity() {
        for (PoliceOfficer off : officers.values()) {
            if (off.isAlive()) return off.entity;
        }
        return null;
    }

    public boolean isPolice(Entity e) {
        if (e == null) return false;
        for (PoliceOfficer off : officers.values()) {
            if (off.entity != null && off.entity.getUniqueId().equals(e.getUniqueId())) return true;
        }
        if (e instanceof LivingEntity) {
            String name = ((LivingEntity) e).getCustomName();
            if (name != null && name.contains("POLICÍA")) return true;
        }
        return false;
    }

    public PoliceOfficer getOfficerByEntity(Entity e) {
        if (e == null) return null;
        for (PoliceOfficer off : officers.values()) {
            if (off.entity != null && off.entity.getUniqueId().equals(e.getUniqueId())) return off;
        }
        if (e instanceof LivingEntity) {
            String cName = ((LivingEntity) e).getCustomName();
            if (cName != null) {
                for (PoliceOfficer off : officers.values()) {
                    if (cName.contains(off.name)) return off;
                }
            }
        }
        return null;
    }

    public synchronized void rebuildPatrolSectors() {
        sectors.clear();
        World w = Bukkit.getWorld("world");
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        if (w == null) return;

        // 1. Sector Central: Plaza Central y Casa de Xavi (Superficie)
        PatrolSector central = new PatrolSector("central", "Plaza Central y Casa de Xavi [Superficie]", w.getName(), false);
        central.addWaypoint(new Location(w, -272.5, 77.0, -6.5));
        central.addWaypoint(new Location(w, -285.0, 77.0, -8.0));
        central.addWaypoint(new Location(w, -275.0, 76.0, 5.0));
        central.addWaypoint(new Location(w, -264.0, 76.0, -2.0));
        sectors.put(central.id, central);

        // 2. Sector Casas y Residencial: Perímetro de casas de jugadores
        PatrolSector casas = new PatrolSector("casas_residencial", "Zona Residencial y Casas de Jugadores [Superficie]", w.getName(), false);
        casas.addWaypoint(new Location(w, -270.5, 76.0, -3.5)); // Entrada Xavi
        casas.addWaypoint(new Location(w, -363.5, 96.0, -48.5)); // Casa taetti
        casas.addWaypoint(new Location(w, -366.5, 96.0, -44.5)); // Casa Leo
        casas.addWaypoint(new Location(w, -11.5, 76.0, -80.5));  // Casa Piccino
        casas.addWaypoint(new Location(w, -6.5, 76.0, -80.5));   // Granja Piccino
        casas.addWaypoint(new Location(w, -819.5, 67.0, -720.5)); // Avanzada taetti
        sectors.put(casas.id, casas);

        // 3. Sector Cuevas Exploradas con Antorchas (Subterráneo)
        PatrolSector cuevas = new PatrolSector("cuevas_antorchas", "Cuevas Exploradas con Antorchas [Subterráneo]", w.getName(), true);
        cuevas.addWaypoint(new Location(w, -255.5, 65.0, -13.5)); // Galería iluminada entrada
        cuevas.addWaypoint(new Location(w, -252.5, 61.0, -25.5)); // Galería antorchas P_Diddy
        cuevas.addWaypoint(new Location(w, -370.5, 61.0, -35.5)); // Caverna antorchas taetti
        cuevas.addWaypoint(new Location(w, -20.5, 30.0, -90.5));  // Túnel con antorchas Este
        cuevas.addWaypoint(new Location(w, -787.5, 35.0, -140.5)); // Galería iluminada Sur
        cuevas.addWaypoint(new Location(w, -760.5, 32.0, -160.5)); // Tramo cuevas iluminadas
        sectors.put(cuevas.id, cuevas);

        // 4. Sector Minas Profundas de Diamantes (Subterráneo Y=11..19)
        PatrolSector subDiamantes = new PatrolSector("sub_minas_diamantes", "Minas Profundas Diamantes [Subterráneo Y=11-19]", w.getName(), true);
        subDiamantes.addWaypoint(new Location(w, -32.5, 11.0, -69.5)); // Pique activo Piccino
        subDiamantes.addWaypoint(new Location(w, -16.5, 19.0, -137.5)); // Galería diamantes Leo
        subDiamantes.addWaypoint(new Location(w, -25.5, 12.0, -100.5));
        subDiamantes.addWaypoint(new Location(w, -799.5, 47.0, -187.5)); // Búnker Sur
        subDiamantes.addWaypoint(new Location(w, -752.5, 27.0, -182.5));
        sectors.put(subDiamantes.id, subDiamantes);

        // 5. Sector Dinámico Jugadores (Rastreo de presencia de jugadores activos)
        PatrolSector dinamico = new PatrolSector("dinamico_jugadores", "Zona Dinámica de Jugadores Activos", w.getName(), false);
        dinamico.addWaypoint(new Location(w, -272.5, 77.0, -6.5));
        sectors.put(dinamico.id, dinamico);

        // Incorporar todos los cofres registrados en ChestManager automáticamente
        if (plugin.getChestManager() != null) {
            for (ChestManager.ChestData cd : plugin.getChestManager().getAllChests().values()) {
                boolean isUnder = cd.y < 63;
                Location cLoc = new Location(w, cd.x + 0.5, cd.y, cd.z + 0.5);

                if (isUnder) {
                    cuevas.addWaypoint(cLoc);
                } else {
                    casas.addWaypoint(cLoc);
                }
            }
        }

        // Asignar sectores iniciales a cada oficial
        for (PoliceOfficer off : officers.values()) {
            PatrolSector target = sectors.get(off.defaultSectorId);
            if (target != null) off.activeSector = target;
            else off.activeSector = central;
        }

        plugin.getLogger().info("[PoliceManager] Inicializados " + sectors.size() + " sectores y 5 agentes policiales.");
    }

    public void registerPlayerConstruction(Location loc) {
        if (loc == null) return;
        boolean isUnder = loc.getBlockY() < 63;
        PatrolSector targetSector = isUnder ? sectors.get("cuevas_antorchas") : sectors.get("casas_residencial");
        if (targetSector != null) {
            targetSector.addWaypoint(loc.clone().add(0.5, 0.0, 0.5));
        }
    }

    public void registerTorch(Location loc) {
        if (loc == null) return;
        PatrolSector cuevas = sectors.get("cuevas_antorchas");
        if (cuevas != null) {
            cuevas.addWaypoint(loc.clone().add(0.5, 0.0, 0.5));
        }
    }

    public synchronized void spawnPolice() {
        World w = Bukkit.getWorld("world");
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        if (w == null) return;

        for (PoliceOfficer off : officers.values()) {
            if (off.killedThisSession) continue;
            if (off.isAlive()) continue;

            spawnOfficer(off, w);
        }
    }

    private void spawnOfficer(PoliceOfficer off, World w) {
        Location spawnLoc = null;
        if (off.activeSector != null && !off.activeSector.waypoints.isEmpty()) {
            spawnLoc = off.activeSector.waypoints.get(0).clone();
        } else {
            spawnLoc = new Location(w, -272.5, 77.0, -6.5, 0f, 0f);
        }
        spawnLoc = findSafeStandingLocation(spawnLoc);

        Zombie cop = (Zombie) w.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        cop.setBaby(false);
        cop.setVillager(false);
        cop.setCanPickupItems(false);
        cop.setRemoveWhenFarAway(false);

        cop.setCustomName(off.customName);
        cop.setCustomNameVisible(true);

        cop.setMaxHealth(60.0);
        cop.setHealth(60.0);

        EntityEquipment eq = cop.getEquipment();

        // Casco
        ItemStack helmet = new ItemStack(off.helmetMaterial);
        ItemMeta hMeta = helmet.getItemMeta();
        hMeta.setDisplayName(ChatColor.BLUE + "Gorra Oficial de " + off.name);
        helmet.setItemMeta(hMeta);
        eq.setHelmet(helmet);
        eq.setHelmetDropChance(0.0f);

        // Chaleco
        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta cMeta = (LeatherArmorMeta) chest.getItemMeta();
        cMeta.setColor(off.armorColor);
        cMeta.setDisplayName(ChatColor.BLUE + "Chaleco Oficial de " + off.name);
        chest.setItemMeta(cMeta);
        eq.setChestplate(chest);
        eq.setChestplateDropChance(0.0f);

        // Pantalones
        ItemStack legs = new ItemStack(Material.LEATHER_LEGGINGS);
        LeatherArmorMeta lMeta = (LeatherArmorMeta) legs.getItemMeta();
        lMeta.setColor(Color.fromRGB(20, 25, 45));
        lMeta.setDisplayName(ChatColor.BLUE + "Pantalón Táctico Reglamentario");
        legs.setItemMeta(lMeta);
        eq.setLeggings(legs);
        eq.setLeggingsDropChance(0.0f);

        // Botas
        ItemStack boots = new ItemStack(Material.IRON_BOOTS);
        boots.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_FALL, 4);
        boots.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER, 3);
        ItemMeta bMeta = boots.getItemMeta();
        bMeta.setDisplayName(ChatColor.BLUE + "Botas Tácticas de Salto y Agilidad");
        boots.setItemMeta(bMeta);
        eq.setBoots(boots);
        eq.setBootsDropChance(0.0f);

        // Porra Policial
        ItemStack baton = new ItemStack(Material.IRON_SWORD);
        ItemMeta batonMeta = baton.getItemMeta();
        batonMeta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Porra Policial Reglamentaria");
        baton.setItemMeta(batonMeta);
        eq.setItemInHand(baton);
        eq.setItemInHandDropChance(0.0f);

        cop.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 999999, 1, false, false));
        cop.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 2, false, false)); // Speed III atlético
        cop.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 999999, 1, false, false));  // Salto Boost II (parkour)
        cop.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 999999, 0, false, false));

        try {
            if (cop instanceof CraftCreature) {
                ((CraftCreature) cop).getHandle().getAttributeInstance(net.minecraft.server.v1_8_R3.GenericAttributes.MOVEMENT_SPEED).setValue(0.35D);
            }
        } catch (Exception ignored) {}

        off.entity = cop;
        plugin.getLogger().info("[PoliceManager] " + off.name + " (" + off.rank + ") desplegado en " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + " (" + (off.activeSector != null ? off.activeSector.name : "Zona Asignada") + ")");
    }

    public void removePolice() {
        for (PoliceOfficer off : officers.values()) {
            if (off.entity != null && off.entity.isValid()) {
                off.entity.remove();
            }
            off.entity = null;
        }
    }

    private void startPatrolLoop() {
        if (patrolTaskId != -1) {
            Bukkit.getScheduler().cancelTask(patrolTaskId);
        }

        patrolTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (Bukkit.getOnlinePlayers().isEmpty()) return;

                for (PoliceOfficer off : officers.values()) {
                    if (off.killedThisSession) continue;

                    if (!off.isAlive()) {
                        spawnPolice();
                        continue;
                    }

                    // Evitar aggro automático a jugadores pacíficos
                    if (off.entity instanceof Creature) {
                        Creature cr = (Creature) off.entity;
                        if (cr.getTarget() instanceof Player) {
                            Player tp = (Player) cr.getTarget();
                            if (!tp.isOnline() || tp.isDead()) {
                                cr.setTarget(null);
                            }
                        }
                    }

                    off.ticksInCurrentSector += 4;
                    if (off.suspicionCooldown > 0) off.suspicionCooldown -= 4;

                    patrolOfficerStep(off);
                }
            }
        }, 40L, 50L); // Cada 2.5 segundos procesa ciclo de patrullaje ultrarrápido
    }

    private void patrolOfficerStep(PoliceOfficer off) {
        if (!off.isAlive()) return;
        Location pLoc = off.entity.getLocation();

        // 1. COMPORTAMIENTO ESPECÍFICO DEL AGENTE NAVARRO (Intervención Rápida cerca de jugadores activos)
        if (off.id.equals("navarro")) {
            Player nearestPlayer = null;
            double nearestDist = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(pLoc.getWorld())) {
                    double d = p.getLocation().distance(pLoc);
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearestPlayer = p;
                    }
                }
            }
            if (nearestPlayer != null) {
                Location pTarget = nearestPlayer.getLocation();
                if (nearestDist > 45.0) {
                    // Desplazamiento táctico rápido si los jugadores están muy lejos
                    Location safe = findSafeStandingLocation(pTarget.clone().add(random.nextInt(7) - 3, 0, random.nextInt(7) - 3));
                    off.entity.teleport(safe);
                    off.entity.getWorld().playSound(safe, Sound.NOTE_PLING, 1.0f, 2.0f);
                } else if (nearestDist > 8.0) {
                    try {
                        if (off.entity instanceof CraftCreature) {
                            ((CraftCreature) off.entity).getHandle().getNavigation().a(pTarget.getX(), pTarget.getY(), pTarget.getZ(), 1.65);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. INSPECCIÓN DE SOSPECHOSOS EN EL CAMPO VISUAL
        Player suspect = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(pLoc.getWorld())) {
                double dist = p.getLocation().distance(pLoc);
                if (dist < 18.0 && off.canSee(p.getEyeLocation())) {
                    // Sigilo cerca de propiedades
                    if (p.isSneaking()) {
                        suspect = p;
                        break;
                    }
                    // Armas desenfundadas en zonas habitadas
                    ItemStack hand = p.getItemInHand();
                    if (hand != null && (hand.getType().name().contains("SWORD") || hand.getType().name().contains("AXE") || hand.getType() == Material.BOW)) {
                        if (dist < 9.0 && random.nextDouble() < 0.25 && off.suspicionCooldown <= 0) {
                            p.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.YELLOW + "Por favor " + p.getName() + ", envaina tu arma mientras estés en zona habitada.");
                            p.playSound(p.getLocation(), Sound.NOTE_PLING, 1.0f, 2.0f);
                            off.suspicionCooldown = 20;
                        }
                    }
                }
            }
        }

        // Si hay sospechoso en sigilo, el agente se acerca a interrogarlo
        if (suspect != null && off.suspicionCooldown <= 0) {
            Location sLoc = suspect.getLocation();
            Vector toSuspect = sLoc.toVector().subtract(pLoc.toVector()).normalize().multiply(3.0);
            double destX = sLoc.getX() - toSuspect.getX();
            double destY = sLoc.getY();
            double destZ = sLoc.getZ() - toSuspect.getZ();

            try {
                if (off.entity instanceof CraftCreature) {
                    ((CraftCreature) off.entity).getHandle().getNavigation().a(destX, destY, destZ, 1.50);
                }
            } catch (Exception ignored) {}

            suspect.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.YELLOW + "¿Qué haces en sigilo por aquí, " + suspect.getName() + "? Mantén la compostura.");
            suspect.playSound(suspect.getLocation(), Sound.NOTE_PLING, 1.0f, 2.0f);
            off.suspicionCooldown = 18;
            return;
        }

        // 3. PATRULLA POR WAYPOINTS DEL SECTOR ACTIVO DEL OFICIAL
        if (off.activeSector != null && !off.activeSector.waypoints.isEmpty()) {
            Location targetWp = off.activeSector.waypoints.get(off.waypointIndex);
            off.waypointIndex = (off.waypointIndex + 1) % off.activeSector.waypoints.size();

            double distSq = pLoc.distanceSquared(targetWp);
            if (distSq > 35.0 * 35.0 || Math.abs(pLoc.getBlockY() - targetWp.getBlockY()) > 10) {
                // Desplazamiento táctico si está en otro piso o muy distante
                Location safe = findSafeStandingLocation(targetWp);
                off.entity.teleport(safe);
            } else {
                try {
                    if (off.entity instanceof CraftCreature) {
                        ((CraftCreature) off.entity).getHandle().getNavigation().a(targetWp.getX(), targetWp.getY(), targetWp.getZ(), 1.40);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Informe sutil de rutina (5% prob si hay gente cerca)
        if (random.nextDouble() < 0.05) {
            for (Entity e : off.entity.getNearbyEntities(12, 5, 12)) {
                if (e instanceof Player) {
                    ((Player) e).sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.GRAY + "Patrullando " + (off.activeSector != null ? off.activeSector.name : "sector asignado") + ". Sin incidentes.");
                    break;
                }
            }
        }
    }

    private Location findSafeStandingLocation(Location loc) {
        Location safe = loc.clone();
        World w = safe.getWorld();
        int x = safe.getBlockX();
        int y = safe.getBlockY();
        int z = safe.getBlockZ();

        for (int dy = 0; dy <= 4; dy++) {
            Block feet = w.getBlockAt(x, y + dy, z);
            Block head = w.getBlockAt(x, y + dy + 1, z);
            Block ground = w.getBlockAt(x, y + dy - 1, z);
            if (!feet.getType().isSolid() && !head.getType().isSolid() && ground.getType().isSolid()) {
                return new Location(w, x + 0.5, y + dy, z + 0.5);
            }
        }
        for (int dy = -1; dy >= -4; dy--) {
            Block feet = w.getBlockAt(x, y + dy, z);
            Block head = w.getBlockAt(x, y + dy + 1, z);
            Block ground = w.getBlockAt(x, y + dy - 1, z);
            if (!feet.getType().isSolid() && !head.getType().isSolid() && ground.getType().isSolid()) {
                return new Location(w, x + 0.5, y + dy, z + 0.5);
            }
        }
        return safe;
    }

    public void handleAttackOnPolice(Entity victimEntity, Player attacker) {
        PoliceOfficer off = getOfficerByEntity(victimEntity);
        if (off == null) {
            // Si no se encuentra directo, asignar al más cercano
            double minDist = Double.MAX_VALUE;
            for (PoliceOfficer o : officers.values()) {
                if (o.isAlive()) {
                    double d = o.entity.getLocation().distance(attacker.getLocation());
                    if (d < minDist) {
                        minDist = d;
                        off = o;
                    }
                }
            }
        }
        if (off == null || !off.isAlive()) return;

        boolean sawAttacker = off.canSee(attacker.getEyeLocation());

        if (!sawAttacker) {
            // Golpe por la espalda en sigilo
            attacker.sendMessage(ChatColor.DARK_GRAY + "🗡 [Sigilo] Has golpeado al " + off.name + " por la espalda sin que te vea. No se ha enterado de quién fue.");
            return;
        }

        // Golpe de frente visto por el policía
        off.entity.getWorld().playSound(off.entity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        off.entity.getWorld().playSound(off.entity.getLocation(), Sound.ANVIL_LAND, 0.8f, 1.5f);

        Vector toAttacker = attacker.getLocation().toVector().subtract(off.entity.getLocation().toVector());
        Location faceLoc = off.entity.getLocation().setDirection(toAttacker);
        off.entity.teleport(faceLoc);

        if (off.entity instanceof Creature) {
            ((Creature) off.entity).setTarget(attacker);
        }

        // Táser reglamentario
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 120, 2));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 1));

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.RED + "¡ALTO! ¡Agresión directa a un agente cometida por " + ChatColor.YELLOW + attacker.getName() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        applyPoliceSanction(off, attacker, "Agresión directa a un agente de policía");
    }

    public void handleAttackOnPolice(Player attacker) {
        handleAttackOnPolice(null, attacker);
    }

    public void handlePoliceDeath(Entity victimEntity) {
        PoliceOfficer off = getOfficerByEntity(victimEntity);
        if (off == null) {
            for (PoliceOfficer o : officers.values()) {
                if (o.entity == null || !o.entity.isValid() || o.entity.isDead()) {
                    off = o;
                    break;
                }
            }
        }
        if (off != null) {
            off.killedThisSession = true;
            off.entity = null;

            int activeCount = 0;
            for (PoliceOfficer o : officers.values()) {
                if (!o.killedThisSession) activeCount++;
            }

            Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");
            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "🚨 [POLICÍA NACIONAL] ¡El " + off.name + " ha caído en cumplimiento de su deber!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Asignación afectada: " + off.assignment);
            Bukkit.broadcastMessage(ChatColor.GRAY + "Agentes restantes en servicio: " + activeCount + " de " + officers.size() + " (Volverá a estar activo al reiniciar el servidor).");
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");

            plugin.getLogger().warning("[PoliceManager] " + off.name + " ha sido asesinado. Permanece fuera de servicio esta sesión.");
        }
    }

    public void handlePoliceDeath() {
        handlePoliceDeath(null);
    }

    public void witnessCrime(Player culprit, String crimeTitle, String description, Location crimeLocation) {
        PoliceOfficer witnessingOfficer = null;
        double minDist = Double.MAX_VALUE;

        // Comprobar qué agente tiene línea visual directa hacia el delito
        for (PoliceOfficer off : officers.values()) {
            if (off.isAlive() && !off.killedThisSession && off.canSee(crimeLocation)) {
                double dist = off.entity.getLocation().distance(crimeLocation);
                if (dist < minDist) {
                    minDist = dist;
                    witnessingOfficer = off;
                }
            }
        }

        if (witnessingOfficer == null) return; // Ningún policía vio el delito físicamente

        witnessingOfficer.entity.getWorld().playSound(witnessingOfficer.entity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        witnessingOfficer.entity.getWorld().playSound(witnessingOfficer.entity.getLocation(), Sound.IRONGOLEM_HIT, 1.0f, 1.0f);

        Vector dir = crimeLocation.toVector().subtract(witnessingOfficer.entity.getLocation().toVector());
        witnessingOfficer.entity.teleport(witnessingOfficer.entity.getLocation().setDirection(dir));

        String locDesc = (witnessingOfficer.activeSector != null ? witnessingOfficer.activeSector.name : "Zona Vigilada");

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + witnessingOfficer.name + ": " + ChatColor.RED + "¡ALTO EN NOMBRE DE LA LEY, " + ChatColor.YELLOW + culprit.getName().toUpperCase() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "  Infracción presenciada en " + ChatColor.AQUA + locDesc + ChatColor.YELLOW + ": " + ChatColor.WHITE + crimeTitle + " (" + description + ")");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        applyPoliceSanction(witnessingOfficer, culprit, crimeTitle);
    }

    public void applyPoliceSanction(PoliceOfficer officer, final Player culprit, String reason) {
        if (culprit == null || !culprit.isOnline()) return;

        if (culprit.getName().equalsIgnoreCase(plugin.getOwnerName())) {
            culprit.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + officer.name + ": " + ChatColor.GREEN + "Saludos, Don Xavi. Disculpe la confusión, continúe.");
            return;
        }

        if (plugin.getHonorManager() != null) {
            plugin.getHonorManager().addHonor(culprit, -20, "Infracción vista por la policía: " + reason);
        }

        List<String> warns = plugin.getPlayerWarnings().get(culprit.getName().toLowerCase());
        int warnCount = (warns != null ? warns.size() : 0) + 1;

        plugin.addWarning(culprit.getName(), "Visto por " + officer.name + ": " + reason, "Policía");

        if (warnCount == 1) {
            culprit.sendMessage(ChatColor.GOLD + "🚔 [POLICÍA] Tienes 1 advertencia oficial. Si acumulas 4 serás expulsado o baneado del servidor.");
            culprit.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 1));
        } else if (warnCount == 2) {
            culprit.sendMessage(ChatColor.GOLD + "🚔 [POLICÍA] ¡SEGUNDA ADVERTENCIA! Quedas inmovilizado temporalmente por desacato.");
            culprit.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 200, 3));
            culprit.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 1));
        } else if (warnCount == 3) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "🚔 [POLICÍA] ¡El jugador " + ChatColor.RED + culprit.getName() + ChatColor.GOLD + " ha sido EXPULSADO del servidor por reincidencia delictiva!");
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    culprit.kickPlayer(ChatColor.GOLD + "" + ChatColor.BOLD + "¡EXPULSADO POR LA POLICÍA!\n\n"
                            + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + "Reincidencia de delitos graves presenciados por la policía nacional (3 advertencias).\n"
                            + ChatColor.AQUA + "Puedes volver a entrar, pero a la 4ª advertencia serás baneado permanentemente.");
                }
            });
        } else if (warnCount >= 4) {
            plugin.banPlayer(culprit.getName(), "Reincidencia extrema de delitos graves presenciados por la policía (4 advertencias)");
        }
    }

    public void dispatchOfficerToSector(PoliceOfficer off, PatrolSector targetSector) {
        if (off == null || targetSector == null) return;
        off.activeSector = targetSector;
        off.ticksInCurrentSector = 0;
        off.waypointIndex = 0;

        if (off.isAlive() && !targetSector.waypoints.isEmpty()) {
            Location dest = findSafeStandingLocation(targetSector.waypoints.get(0));
            off.entity.teleport(dest);
            off.entity.getWorld().playSound(dest, Sound.NOTE_PLING, 1.2f, 1.8f);
            plugin.getLogger().info("[PoliceManager] " + off.name + " desplazado a patrullar: " + targetSector.name);
        }
    }

    public void dispatchToSector(PatrolSector targetSector) {
        PoliceOfficer torres = officers.get("torres");
        if (torres != null) dispatchOfficerToSector(torres, targetSector);
    }

    public Map<String, PatrolSector> getSectors() {
        return sectors;
    }

    public PatrolSector getActiveSector() {
        PoliceOfficer torres = officers.get("torres");
        return torres != null ? torres.activeSector : null;
    }
}
