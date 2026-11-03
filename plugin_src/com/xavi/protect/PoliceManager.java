package com.xavi.protect;

import net.minecraft.server.v1_8_R3.EntityCreature;
import net.minecraft.server.v1_8_R3.EntityInsentient;
import net.minecraft.server.v1_8_R3.GenericAttributes;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.PacketPlayOutTitle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftCreature;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
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
    private static java.lang.reflect.Field FIELD_TARGET_B = null;
    private static java.lang.reflect.Field FIELD_TARGET_C = null;

    static {
        try {
            Class<?> pgsClass = net.minecraft.server.v1_8_R3.PathfinderGoalSelector.class;
            FIELD_TARGET_B = pgsClass.getDeclaredField("b");
            FIELD_TARGET_B.setAccessible(true);
            FIELD_TARGET_C = pgsClass.getDeclaredField("c");
            FIELD_TARGET_C.setAccessible(true);
        } catch (Exception ignored) {}
    }


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
        public Player targetPlayer = null;
        public int pursuitTicks = 0;
        public long lastBatonStrike = 0L;

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
            return !killedThisSession && entity != null && !entity.isDead();
        }

        public boolean isChunkLoaded() {
            if (entity == null) return false;
            Location loc = entity.getLocation();
            World w = loc.getWorld();
            if (w == null) return false;
            return w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        }

        public boolean canSee(Location targetLoc) {
            if (!isAlive() || !isChunkLoaded()) return false;
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

            // Raycast optimizado sin asignaciones masivas de memoria
            double startX = eye.getX();
            double startY = eye.getY();
            double startZ = eye.getZ();

            double dx = targetLoc.getX() - startX;
            double dy = targetLoc.getY() - startY;
            double dz = targetLoc.getZ() - startZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0) return true;

            double stepX = (dx / length) * 0.5;
            double stepY = (dy / length) * 0.5;
            double stepZ = (dz / length) * 0.5;
            int steps = (int) (length / 0.5);

            World w = eye.getWorld();
            int lastBx = Integer.MIN_VALUE, lastBy = Integer.MIN_VALUE, lastBz = Integer.MIN_VALUE;

            double curX = startX;
            double curY = startY;
            double curZ = startZ;

            for (int i = 0; i < steps - 1; i++) {
                curX += stepX;
                curY += stepY;
                curZ += stepZ;
                int bx = (int) Math.floor(curX);
                int by = (int) Math.floor(curY);
                int bz = (int) Math.floor(curZ);

                if (bx != lastBx || by != lastBy || bz != lastBz) {
                    lastBx = bx;
                    lastBy = by;
                    lastBz = bz;
                    Block b = w.getBlockAt(bx, by, bz);
                    if (b != null && isVisionBlocking(b)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean isVisionBlocking(Block b) {
            if (b == null) return false;
            Material m = b.getType();
            if (m == null || m == Material.AIR) return false;
            if (m == Material.GLASS || m == Material.THIN_GLASS || m == Material.STAINED_GLASS || m == Material.STAINED_GLASS_PANE) return false;
            if (m == Material.FENCE || m == Material.IRON_FENCE || m == Material.NETHER_FENCE) return false;
            if (m == Material.TORCH || m == Material.WATER || m == Material.STATIONARY_WATER) return false;
            if (m == Material.SIGN_POST || m == Material.WALL_SIGN || m == Material.CARPET || m == Material.LADDER) return false;
            // Puertas y vallas abiertas no bloquean la visión policial
            if (isDoorBlock(b) && isDoorOpen(b)) return false;
            return m.isOccluding() || m.isSolid();
        }
    }

    private final XaviAdminProtect plugin;
    private final Map<String, PoliceOfficer> officers = new LinkedHashMap<String, PoliceOfficer>();
    private final Map<String, PatrolSector> sectors = new LinkedHashMap<String, PatrolSector>();
    private int patrolTaskId = -1;
    private int doorCheckTaskId = -1;
    private final Map<Location, Long> activePoliceDoors = new LinkedHashMap<Location, Long>();
    private final Random random = new Random();
    private final Map<String, Integer> playerAccidentalHits = new LinkedHashMap<String, Integer>();
    private final Map<String, Long> lastHitTimestamp = new LinkedHashMap<String, Long>();

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
                "Plaza Central y Casa de Xavi [Superficie]",
                Color.fromRGB(16, 44, 87), Material.CHAINMAIL_HELMET, "central"));

        // 2. Agente Romero - Guardia de Plaza y Vías Públicas
        officers.put("romero", new PoliceOfficer(
                "romero", "Agente Romero", "Guardia de Plaza",
                "Caminos y Almacén Central [Superficie]",
                Color.fromRGB(22, 55, 95), Material.CHAINMAIL_HELMET, "central"));

        // 3. Agente Morales - Guardia Residencial Norte (Casas de Taetti y Leo)
        officers.put("morales", new PoliceOfficer(
                "morales", "Agente Morales", "Guardia Residencial Norte",
                "Barrio Norte y Casas de Taetti y Leo [Superficie]",
                Color.fromRGB(20, 60, 120), Material.LEATHER_HELMET, "norte_residencial"));

        // 4. Agente Herrera - Unidad Táctica y Pacificación Norte
        officers.put("herrera", new PoliceOfficer(
                "herrera", "Agente Herrera", "Guardia Táctico Norte",
                "Interiores y Calles Residenciales Norte",
                Color.fromRGB(15, 50, 100), Material.IRON_HELMET, "norte_residencial"));

        // 5. Agente Delgado - Guardia Residencial Este (Casa y Granja de Piccino)
        officers.put("delgado", new PoliceOfficer(
                "delgado", "Agente Delgado", "Guardia Residencial Este",
                "Barrio Este, Casa y Granja de Piccino [Superficie]",
                Color.fromRGB(15, 75, 110), Material.LEATHER_HELMET, "este_residencial"));

        // 6. Agente Vargas - Custodio de Construcciones e Interiores
        officers.put("vargas", new PoliceOfficer(
                "vargas", "Agente Vargas", "Custodio de Construcciones",
                "Construcciones e Interiores de Viviendas Este",
                Color.fromRGB(20, 70, 115), Material.LEATHER_HELMET, "este_residencial"));

        // 7. Agente Ramírez - Inspector de Minas Profundas (Subterráneo Y=11-19)
        officers.put("ramirez", new PoliceOfficer(
                "ramirez", "Agente Ramírez", "Inspector de Minas",
                "Minas Profundas Diamantes [Subterráneo Y=11-19]",
                Color.fromRGB(25, 35, 60), Material.IRON_HELMET, "sub_minas_diamantes"));

        // 8. Agente Vega - Vigilante Espeleológico (Cuevas con Antorchas)
        officers.put("vega", new PoliceOfficer(
                "vega", "Agente Vega", "Vigilante de Cuevas",
                "Cuevas Exploradas con Antorchas [Subterráneo]",
                Color.fromRGB(30, 50, 75), Material.CHAINMAIL_HELMET, "cuevas_antorchas"));

        // 9. Agente Mendoza - Vigilante de Búnker Sur (-800, 40, -180)
        officers.put("mendoza", new PoliceOfficer(
                "mendoza", "Agente Mendoza", "Vigilante de Búnker",
                "Búnker Subterráneo Sur y Galerías de Seguridad",
                Color.fromRGB(28, 40, 65), Material.IRON_HELMET, "sur_bunker"));

        // 10. Agente Ortega - Patrulla de Avanzada Sur (-819, 67, -720)
        officers.put("ortega", new PoliceOfficer(
                "ortega", "Agente Ortega", "Patrulla de Avanzada",
                "Avanzada y Perímetro Sur",
                Color.fromRGB(25, 65, 105), Material.CHAINMAIL_HELMET, "sur_bunker"));

        // 11. Agente Navarro - Unidad de Intervención Rápida 1 (Sigue dinámicamente a los jugadores)
        officers.put("navarro", new PoliceOfficer(
                "navarro", "Agente Navarro", "Unidad Rápida 1",
                "Vigilancia Dinámica Inmediata donde haya Jugadores",
                Color.fromRGB(10, 80, 140), Material.IRON_HELMET, "dinamico_jugadores"));

        // 12. Agente Castillo - Unidad de Intervención Rápida 2 (Sigue dinámicamente a los jugadores)
        officers.put("castillo", new PoliceOfficer(
                "castillo", "Agente Castillo", "Unidad Rápida 2",
                "Vigilancia Dinámica Inmediata donde haya Jugadores",
                Color.fromRGB(10, 75, 135), Material.IRON_HELMET, "dinamico_jugadores"));
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

        // 1. Sector Central: Plaza Central y Casa de Xavi [Superficie]
        PatrolSector central = new PatrolSector("central", "Plaza Central y Casa de Xavi [Superficie]", w.getName(), false);
        central.addWaypoint(new Location(w, -272.5, 77.0, -6.5));
        central.addWaypoint(new Location(w, -270.5, 76.0, -3.5)); // Entrada interior casa Xavi
        central.addWaypoint(new Location(w, -276.5, 67.0, -10.5)); // Sótano y cofres Xavi
        central.addWaypoint(new Location(w, -285.0, 77.0, -8.0));  // Plaza central
        central.addWaypoint(new Location(w, -275.0, 76.0, 5.0));   // Salida sur
        central.addWaypoint(new Location(w, -264.0, 76.0, -2.0));  // Calle este
        sectors.put(central.id, central);

        // 2. Sector Residencial Norte: Casas de Taetti y Leo [Superficie]
        PatrolSector norte = new PatrolSector("norte_residencial", "Barrio Residencial Norte (Taetti y Leo)", w.getName(), false);
        norte.addWaypoint(new Location(w, -363.5, 96.0, -48.5)); // Interior casa Taetti
        norte.addWaypoint(new Location(w, -366.5, 96.0, -44.5)); // Interior casa Leo
        norte.addWaypoint(new Location(w, -358.5, 96.0, -52.5)); // Terraza y mirador
        norte.addWaypoint(new Location(w, -370.5, 95.0, -40.5)); // Acceso colina
        sectors.put(norte.id, norte);

        // 3. Sector Residencial Este: Casa y Granja de Piccino [Superficie]
        PatrolSector este = new PatrolSector("este_residencial", "Barrio Residencial Este (Piccino)", w.getName(), false);
        este.addWaypoint(new Location(w, -11.5, 76.0, -80.5));  // Interior casa Piccino
        este.addWaypoint(new Location(w, -6.5, 76.0, -80.5));   // Granja Piccino
        este.addWaypoint(new Location(w, -15.5, 76.0, -75.5));  // Almacén y entrada
        este.addWaypoint(new Location(w, -20.5, 75.0, -85.5));  // Perímetro exterior
        sectors.put(este.id, este);

        // 4. Sector Cuevas Exploradas con Antorchas (Subterráneo)
        PatrolSector cuevas = new PatrolSector("cuevas_antorchas", "Cuevas Exploradas con Antorchas [Subterráneo]", w.getName(), true);
        cuevas.addWaypoint(new Location(w, -255.5, 65.0, -13.5)); // Galería iluminada entrada
        cuevas.addWaypoint(new Location(w, -252.5, 61.0, -25.5)); // Galería antorchas P_Diddy
        cuevas.addWaypoint(new Location(w, -370.5, 61.0, -35.5)); // Caverna antorchas taetti
        cuevas.addWaypoint(new Location(w, -20.5, 30.0, -90.5));  // Túnel Este
        sectors.put(cuevas.id, cuevas);

        // 5. Sector Minas Profundas de Diamantes (Subterráneo Y=11..19)
        PatrolSector subDiamantes = new PatrolSector("sub_minas_diamantes", "Minas Profundas Diamantes [Subterráneo Y=11-19]", w.getName(), true);
        subDiamantes.addWaypoint(new Location(w, -32.5, 11.0, -69.5)); // Pique activo Piccino
        subDiamantes.addWaypoint(new Location(w, -16.5, 19.0, -137.5)); // Galería diamantes Leo
        subDiamantes.addWaypoint(new Location(w, -25.5, 12.0, -100.5)); // Túnel principal
        subDiamantes.addWaypoint(new Location(w, -45.5, 14.0, -85.5));  // Cruce de galerías
        subDiamantes.addWaypoint(new Location(w, -20.5, 12.0, -120.5)); // Fondo de mina
        sectors.put(subDiamantes.id, subDiamantes);

        // 6. Sector Búnker y Avanzada Sur
        PatrolSector surBunker = new PatrolSector("sur_bunker", "Búnker y Avanzada Sur", w.getName(), false);
        surBunker.addWaypoint(new Location(w, -799.5, 47.0, -187.5)); // Búnker Sur
        surBunker.addWaypoint(new Location(w, -787.5, 35.0, -140.5)); // Galería iluminada Sur
        surBunker.addWaypoint(new Location(w, -752.5, 27.0, -182.5)); // Almacén subterráneo Sur
        surBunker.addWaypoint(new Location(w, -819.5, 67.0, -720.5)); // Avanzada exterior Taetti
        sectors.put(surBunker.id, surBunker);

        // 7. Sector Dinámico Jugadores ("Por donde estemos")
        PatrolSector dinamico = new PatrolSector("dinamico_jugadores", "Zona Dinámica de Jugadores Activos", w.getName(), false);
        dinamico.addWaypoint(new Location(w, -272.5, 77.0, -6.5));
        sectors.put(dinamico.id, dinamico);

        // Incorporar todos los cofres registrados en ChestManager localmente en su sector exacto
        if (plugin.getChestManager() != null) {
            for (ChestManager.ChestData cd : plugin.getChestManager().getAllChests().values()) {
                Location cLoc = new Location(w, cd.x + 0.5, cd.y, cd.z + 0.5);
                if (cd.y < 30) {
                    subDiamantes.addWaypoint(cLoc);
                } else if (cd.y < 63) {
                    cuevas.addWaypoint(cLoc);
                } else if (cd.x < -600) {
                    surBunker.addWaypoint(cLoc);
                } else if (cd.x < -320) {
                    norte.addWaypoint(cLoc);
                } else if (cd.x > -100) {
                    este.addWaypoint(cLoc);
                } else {
                    central.addWaypoint(cLoc);
                }
            }
        }

        // Incorporar camas de jugadores como puntos de patrulla interior
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location bed = p.getBedSpawnLocation();
            if (bed != null && bed.getWorld().equals(w)) {
                if (bed.getX() < -600) surBunker.addWaypoint(bed);
                else if (bed.getX() < -320) norte.addWaypoint(bed);
                else if (bed.getX() > -100) este.addWaypoint(bed);
                else central.addWaypoint(bed);
            }
        }

        // Asignar sectores iniciales a cada oficial
        for (PoliceOfficer off : officers.values()) {
            PatrolSector target = sectors.get(off.defaultSectorId);
            if (target != null) off.activeSector = target;
            else off.activeSector = central;
        }

        plugin.getLogger().info("[PoliceManager] Inicializados " + sectors.size() + " sectores y " + officers.size() + " agentes policiales.");
    }

    public void registerPlayerConstruction(Location loc) {
        if (loc == null) return;
        boolean isUnder = loc.getBlockY() < 63;
        PatrolSector targetSector;
        if (isUnder) {
            targetSector = (loc.getBlockY() < 30 ? sectors.get("sub_minas_diamantes") : sectors.get("cuevas_antorchas"));
        } else {
            if (loc.getX() < -320) targetSector = sectors.get("norte_residencial");
            else if (loc.getX() > -100) targetSector = sectors.get("este_residencial");
            else targetSector = sectors.get("central");
        }
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
            if (off.entity != null && !off.entity.isDead()) continue;

            spawnOfficer(off, w);
        }
    }

    private void spawnOfficer(PoliceOfficer off, World w) {
        if (off.killedThisSession) return;
        if (off.entity != null && !off.entity.isDead()) return;

        // Limpiar cualquier zombi huérfano con este nombre antes de desplegar
        for (Entity e : w.getEntities()) {
            if (e instanceof LivingEntity) {
                String cName = ((LivingEntity) e).getCustomName();
                if (cName != null && cName.contains(off.name)) {
                    e.remove();
                }
            }
        }

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
        bMeta.setDisplayName(ChatColor.BLUE + "Botas de Patrulla Reglamentarias");
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
        cop.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 999999, 0, false, false));

        try {
            if (cop instanceof CraftCreature) {
                net.minecraft.server.v1_8_R3.EntityInsentient nms = ((CraftCreature) cop).getHandle();
                // Velocidad serena y realista de patrullaje a pie (0.24D, paso natural sin correr)
                nms.getAttributeInstance(net.minecraft.server.v1_8_R3.GenericAttributes.MOVEMENT_SPEED).setValue(0.24D);

                // Silenciar por completo al policía para eliminar los gruñidos y gemidos molestos de zombi (Silent: 1b)
                nms.b(true);

                // Desactivar completamente la agresividad hostil del zombi hacia los jugadores mediante campos estáticos cacheados
                if (FIELD_TARGET_B != null && FIELD_TARGET_C != null) {
                    ((java.util.List<?>) FIELD_TARGET_B.get(nms.targetSelector)).clear();
                    ((java.util.List<?>) FIELD_TARGET_C.get(nms.targetSelector)).clear();
                }

                // Habilitar paso a través de puertas en la navegación y apertura automática
                if (nms.getNavigation() instanceof net.minecraft.server.v1_8_R3.Navigation) {
                    net.minecraft.server.v1_8_R3.Navigation nav = (net.minecraft.server.v1_8_R3.Navigation) nms.getNavigation();
                    nav.a(true); // canPassOpenDoors
                    nav.b(true); // canOpenDoors (incluye puertas cerradas en su cálculo de rutas)
                }
                nms.goalSelector.a(2, new net.minecraft.server.v1_8_R3.PathfinderGoalOpenDoor(nms, true));
            }
        } catch (Exception ignored) {}

        off.entity = cop;
        plugin.getLogger().info("[PoliceManager] " + off.name + " (" + off.rank + ") desplegado en " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + " (" + (off.activeSector != null ? off.activeSector.name : "Zona Asignada") + ")");
    }

    public void removePolice() {
        if (doorCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(doorCheckTaskId);
            doorCheckTaskId = -1;
        }

        // Cerrar limpiamente cualquier puerta que hubiera quedado abierta
        for (Location loc : activePoliceDoors.keySet()) {
            closeDoor(loc.getBlock());
        }
        activePoliceDoors.clear();

        for (PoliceOfficer off : officers.values()) {
            if (off.entity != null) {
                try {
                    off.entity.remove();
                } catch (Exception ignored) {}
            }
            off.entity = null;
        }

        // Limpiar cualquier zombi residual con nombre de policía en todos los mundos cargados
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e instanceof LivingEntity) {
                    String name = ((LivingEntity) e).getCustomName();
                    if (name != null && name.contains("POLICÍA")) {
                        e.remove();
                    }
                }
            }
        }
    }

    private void startPatrolLoop() {
        if (patrolTaskId != -1) {
            Bukkit.getScheduler().cancelTask(patrolTaskId);
        }
        if (doorCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(doorCheckTaskId);
        }

        // Tarea rápida de gestión de puertas: cada 0.5s revisa si los policías tienen puertas delante
        doorCheckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                checkAndManagePoliceDoors();
            }
        }, 10L, 10L);

        patrolTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (Bukkit.getOnlinePlayers().isEmpty()) return;

                for (PoliceOfficer off : officers.values()) {
                    if (off.killedThisSession) continue;

                    // Si no tiene entidad asignada o no existe, intentar spawn inicial
                    if (off.entity == null) {
                        World w = Bukkit.getWorld("world");
                        if (w != null) spawnOfficer(off, w);
                        continue;
                    }

                    // Si la entidad murió en combate, no respawnear durante la jornada
                    if (off.entity.isDead()) {
                        continue;
                    }

                    // Si el chunk donde está el policía está descargado porque ningún jugador está cerca,
                    // no forzar la carga del chunk ni ejecutar pasos para evitar tirones de I/O en el servidor
                    if (!off.isChunkLoaded()) {
                        continue;
                    }

                    // Regeneración pasiva de salud (2 HP por ciclo hasta 60 HP)
                    if (off.entity.getHealth() < off.entity.getMaxHealth()) {
                        off.entity.setHealth(Math.min(off.entity.getMaxHealth(), off.entity.getHealth() + 2.0));
                    }

                    // Asegurar que el policía permanezca silenciado en todo momento (sin gruñidos de zombi)
                    try {
                        if (off.entity instanceof CraftCreature) {
                            net.minecraft.server.v1_8_R3.EntityInsentient nms = ((CraftCreature) off.entity).getHandle();
                            if (!nms.R()) {
                                nms.b(true);
                            }
                        }
                    } catch (Exception ignored) {}

                    off.ticksInCurrentSector += 4;
                    if (off.suspicionCooldown > 0) off.suspicionCooldown -= 4;

                    // Persecución activa de delincuentes/agresores
                    if (off.pursuitTicks > 0) {
                        off.pursuitTicks -= 4;
                        if (off.targetPlayer != null && off.targetPlayer.isOnline() && off.targetPlayer.getWorld().equals(off.entity.getWorld())) {
                            double dist = off.entity.getLocation().distance(off.targetPlayer.getLocation());
                            if (dist > 35.0) {
                                off.targetPlayer = null;
                                off.pursuitTicks = 0;
                            } else {
                                try {
                                    if (off.entity instanceof CraftCreature) {
                                        ((CraftCreature) off.entity).getHandle().getNavigation().a(
                                                off.targetPlayer.getLocation().getX(),
                                                off.targetPlayer.getLocation().getY(),
                                                off.targetPlayer.getLocation().getZ(), 1.20);
                                    }
                                } catch (Exception ignored) {}
                                if (dist <= 3.5) {
                                    deliverBatonStrike(off, off.targetPlayer, "¡Alto a la autoridad! Mantén la distancia.");
                                }
                                continue;
                            }
                        } else {
                            off.targetPlayer = null;
                            off.pursuitTicks = 0;
                        }
                    }

                    // Con ciudadanos inocentes nunca toman objetivo hostil
                    if (off.entity instanceof Creature) {
                        ((Creature) off.entity).setTarget(null);
                    }

                    patrolOfficerStep(off);
                }
            }
        }, 40L, 50L); // Cada 2.5 segundos procesa ciclo de patrullaje a pie
    }

    private void patrolOfficerStep(PoliceOfficer off) {
        if (!off.isAlive()) return;
        Location pLoc = off.entity.getLocation();

        // 1. SISTEMA ANTI-ESTORBO Y RESPETO DE ESPACIO PERSONAL
        if (off.pursuitTicks <= 0) {
            Player nearbyCitizen = null;
            double citizenDist = Double.MAX_VALUE;
            for (Entity e : off.entity.getNearbyEntities(3.5, 2.5, 3.5)) {
                if (e instanceof Player) {
                    Player p = (Player) e;
                    double d = p.getLocation().distance(pLoc);
                    if (d < citizenDist) {
                        citizenDist = d;
                        nearbyCitizen = p;
                    }
                }
            }

            if (nearbyCitizen != null && citizenDist < 2.0) {
                Vector stepAway = pLoc.toVector().subtract(nearbyCitizen.getLocation().toVector()).setY(0);
                if (stepAway.lengthSquared() > 0.01) {
                    stepAway.normalize().multiply(0.20);
                    off.entity.setVelocity(stepAway);
                }
                return;
            }
        }

        // 2. ENTRAR EN CONSTRUCCIONES Y VIVIENDAS DE JUGADORES
        Block nearbyDoor = findNearbyDoor(pLoc, 10);
        if (nearbyDoor != null && random.nextDouble() < 0.35) {
            Block master = getMasterDoorBlock(nearbyDoor);
            openDoor(master);
            activePoliceDoors.put(master.getLocation(), System.currentTimeMillis() + 4500L);
            Location insideTarget = findInteriorBehindDoor(master);
            if (insideTarget != null) {
                try {
                    if (off.entity instanceof CraftCreature) {
                        ((CraftCreature) off.entity).getHandle().getNavigation().a(
                                insideTarget.getX(), insideTarget.getY(), insideTarget.getZ(), 1.0);
                    }
                } catch (Exception ignored) {}
                return;
            }
        }

        // 3. UNIDADES DINÁMICAS ("Por donde estemos"): Navarro, Castillo, Herrera, Vargas
        if (off.id.equals("navarro") || off.id.equals("castillo") || off.id.equals("herrera") || off.id.equals("vargas")) {
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
                if (nearestDist < 8.0) {
                    return; // Buena posición de vigilancia
                } else if (nearestDist > 65.0) {
                    Location pLocTarget = nearestPlayer.getLocation();
                    Location safe = findSafeStandingLocation(pLocTarget.clone().add(random.nextInt(16) - 8, 0, random.nextInt(16) - 8));
                    if (safe != null) {
                        if (!safe.getChunk().isLoaded()) safe.getChunk().load(true);
                        if (!off.entity.getLocation().getChunk().isLoaded()) off.entity.getLocation().getChunk().load(true);
                        off.entity.teleport(safe);
                    }
                    return;
                } else if (nearestDist > 12.0) {
                    try {
                        if (off.entity instanceof CraftCreature) {
                            ((CraftCreature) off.entity).getHandle().getNavigation().a(
                                    nearestPlayer.getLocation().getX(),
                                    nearestPlayer.getLocation().getY(),
                                    nearestPlayer.getLocation().getZ(), 1.05);
                        }
                    } catch (Exception ignored) {}
                    return;
                }
            }
        }

        // 4. ACTITUD TRANQUILA Y RESPETUOSA CON CIUDADANOS PACÍFICOS
        if (off.suspicionCooldown <= 0 && random.nextDouble() < 0.02) {
            for (Entity e : off.entity.getNearbyEntities(6, 2, 6)) {
                if (e instanceof Player) {
                    Player p = (Player) e;
                    String[] friendlyMessages = new String[] {
                        "Buenas, " + p.getName() + ". Circule con tranquilidad.",
                        "Saludos, ciudadano. Zona bajo vigilancia.",
                        "En patrulla de seguridad, " + p.getName() + "."
                    };
                    String msg = friendlyMessages[random.nextInt(friendlyMessages.length)];
                    p.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.GRAY + msg);
                    off.suspicionCooldown = 80;
                    break;
                }
            }
        }

        // 5. PATRULLA POR WAYPOINTS LOCALES A PASO NORMAL
        if (off.activeSector != null && !off.activeSector.waypoints.isEmpty()) {
            Location targetWp = off.activeSector.waypoints.get(off.waypointIndex);
            double distSq = pLoc.distanceSquared(targetWp);
            if (distSq < 3.5 * 3.5) {
                off.waypointIndex = (off.waypointIndex + 1) % off.activeSector.waypoints.size();
                targetWp = off.activeSector.waypoints.get(off.waypointIndex);
            }

            World targetWorld = targetWp.getWorld();
            int chunkX = targetWp.getBlockX() >> 4;
            int chunkZ = targetWp.getBlockZ() >> 4;
            if (!targetWorld.isChunkLoaded(chunkX, chunkZ)) {
                targetWorld.loadChunk(chunkX, chunkZ);
            }

            if (distSq > 70.0 * 70.0 || Math.abs(pLoc.getBlockY() - targetWp.getBlockY()) > 16) {
                Location safe = findSafeStandingLocation(targetWp);
                if (safe != null) {
                    if (!safe.getChunk().isLoaded()) safe.getChunk().load(true);
                    if (!off.entity.getLocation().getChunk().isLoaded()) off.entity.getLocation().getChunk().load(true);
                    off.entity.teleport(safe);
                }
            } else {
                try {
                    if (off.entity instanceof CraftCreature) {
                        ((CraftCreature) off.entity).getHandle().getNavigation().a(targetWp.getX(), targetWp.getY(), targetWp.getZ(), 1.0);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public Block findNearbyDoor(Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) return null;
        World w = loc.getWorld();
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (isDoorBlock(b)) {
                            return b;
                        }
                    }
                }
            }
        }
        return null;
    }

    public Location findInteriorBehindDoor(Block door) {
        if (door == null) return null;
        Block master = getMasterDoorBlock(door);
        BlockFace[] faces = new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };
        for (BlockFace face : faces) {
            Block step2 = master.getRelative(face, 2);
            if (step2.getRelative(BlockFace.DOWN).getType().isSolid() && !step2.getType().isSolid()) {
                boolean hasRoof = false;
                for (int dy = 2; dy <= 6; dy++) {
                    if (step2.getRelative(0, dy, 0).getType().isSolid()) {
                        hasRoof = true;
                        break;
                    }
                }
                if (hasRoof) {
                    return step2.getLocation().add(0.5, 0.0, 0.5);
                }
            }
        }
        return master.getRelative(BlockFace.NORTH, 2).getLocation().add(0.5, 0.0, 0.5);
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

    public void sendTitle(Player p, String title, String subtitle) {
        if (p == null || !p.isOnline()) return;
        try {
            CraftPlayer cp = (CraftPlayer) p;
            PacketPlayOutTitle length = new PacketPlayOutTitle(5, 45, 15);
            IChatBaseComponent cTitle = IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + title + "\"}");
            PacketPlayOutTitle pTitle = new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.TITLE, cTitle);
            IChatBaseComponent cSub = IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + subtitle + "\"}");
            PacketPlayOutTitle pSub = new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.SUBTITLE, cSub);

            cp.getHandle().playerConnection.sendPacket(length);
            cp.getHandle().playerConnection.sendPacket(pTitle);
            cp.getHandle().playerConnection.sendPacket(pSub);
        } catch (Exception ignored) {}
    }

    public void deliverBatonStrike(PoliceOfficer off, Player target, String reason) {
        if (off == null || !off.isAlive() || target == null || !target.isOnline()) return;
        if (target.getName().equalsIgnoreCase(plugin.getOwnerName())) return;

        long now = System.currentTimeMillis();
        if (now - off.lastBatonStrike < 1200L) return; // Cooldown mínimo entre porrazos
        off.lastBatonStrike = now;

        // Girar hacia el sospechoso
        Vector toTarget = target.getLocation().toVector().subtract(off.entity.getLocation().toVector());
        Location lookLoc = off.entity.getLocation().setDirection(toTarget);
        off.entity.teleport(lookLoc);

        // Sonidos policiales de contención
        Location tLoc = target.getLocation();
        tLoc.getWorld().playSound(tLoc, Sound.IRONGOLEM_HIT, 1.2f, 0.9f);
        tLoc.getWorld().playSound(tLoc, Sound.ZOMBIE_WOOD, 1.0f, 1.5f);

        // Regla no letal: si tiene <= 4.0 HP (2 corazones), no dañarlo más, solo neutralizar
        if (target.getHealth() <= 4.0) {
            target.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.YELLOW + "¡Quedas neutralizado y bajo custodia policial! No te resistas más.");
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 120, 2));
            sendTitle(target, ChatColor.BLUE + "🚔 [POLICÍA]", ChatColor.YELLOW + "¡Quedas bajo custodia! Mantén la distancia.");
            return;
        }

        // Empujón de retroceso (pushback)
        Vector push = target.getLocation().toVector().subtract(off.entity.getLocation().toVector()).setY(0);
        if (push.lengthSquared() > 0.01) {
            push.normalize().multiply(0.95).setY(0.30);
            target.setVelocity(push);
        }

        // Daño controlado de porra (no letal)
        target.damage(3.5, off.entity);

        // Efectos de aturdimiento y control (taser / porrazo)
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 80, 1)); // Slowness II por 4s
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0)); // Aturdimiento visual de 1s

        target.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.RED + "¡PORRAZO DE CONTENCIÓN! " + ChatColor.YELLOW + reason);
        sendTitle(target, ChatColor.RED + "🚔 ¡ALTO A LA POLICÍA!", ChatColor.YELLOW + reason);
    }

    public void handleAttackOnPolice(Entity victimEntity, Player attacker) {
        if (attacker == null || !attacker.isOnline()) return;

        PoliceOfficer off = getOfficerByEntity(victimEntity);
        if (off == null) {
            // Si no se encuentra directo, asignar al más cercano
            double minDist = Double.MAX_VALUE;
            for (PoliceOfficer o : officers.values()) {
                if (o.isAlive() && o.entity.getWorld().equals(attacker.getWorld())) {
                    double d = o.entity.getLocation().distance(attacker.getLocation());
                    if (d < minDist) {
                        minDist = d;
                        off = o;
                    }
                }
            }
        }
        if (off == null || !off.isAlive()) return;

        // Don Xavi es intocable y respetado
        if (attacker.getName().equalsIgnoreCase(plugin.getOwnerName())) {
            attacker.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.GREEN + "¡A sus órdenes, Don Xavi! Disculpe las molestias.");
            return;
        }

        // Orientación previa antes del giro
        Vector policeDir = off.entity.getLocation().getDirection().setY(0).normalize();
        Vector toAttackerVec = attacker.getEyeLocation().toVector().subtract(off.entity.getEyeLocation().toVector());
        Vector toAttackerFlat = toAttackerVec.clone().setY(0).normalize();
        double dot = policeDir.dot(toAttackerFlat);

        // Girar INMEDIATAMENTE al policía hacia los ojos del atacante
        Location faceLoc = off.entity.getLocation().setDirection(toAttackerVec);
        off.entity.teleport(faceLoc);

        // Aumentar velocidad para persecución activa táctica
        try {
            if (off.entity instanceof CraftCreature) {
                EntityInsentient nms = ((CraftCreature) off.entity).getHandle();
                nms.getAttributeInstance(GenericAttributes.MOVEMENT_SPEED).setValue(0.32D);
            }
        } catch (Exception ignored) {}

        // El sigilo solo es válido si el jugador está agachado (sneak), ataca estrictamente por la espalda (dot < -0.35)
        boolean isStealth = attacker.isSneaking() && dot < -0.35;

        long now = System.currentTimeMillis();
        long last = lastHitTimestamp.containsKey(attacker.getName().toLowerCase()) ? lastHitTimestamp.get(attacker.getName().toLowerCase()) : 0L;
        int hits = (now - last < 15000L) ? (playerAccidentalHits.containsKey(attacker.getName().toLowerCase()) ? playerAccidentalHits.get(attacker.getName().toLowerCase()) : 0) : 0;
        hits++;
        playerAccidentalHits.put(attacker.getName().toLowerCase(), hits);
        lastHitTimestamp.put(attacker.getName().toLowerCase(), now);

        if (isStealth && hits == 1) {
            // Golpe por la espalda en sigilo
            attacker.sendMessage(ChatColor.DARK_GRAY + "🗡 [Sigilo] Has golpeado al " + off.name + " por la espalda en sigilo.");
            off.entity.getWorld().playSound(off.entity.getLocation(), Sound.NOTE_SNARE_DRUM, 1.2f, 0.8f);
            attacker.sendMessage(ChatColor.YELLOW + "🚔 [POLICÍA] " + off.name + ": ¡¿Quién anda ahí detrás?! ¡Te tengo en el punto de mira!");
            return;
        }

        // --- GOLPE EN SU CARA / GOLPE DIRECTO DETECTADO ---
        // Efectos visuales de impacto en la cara
        Location hitLoc = off.entity.getEyeLocation().clone().add(off.entity.getLocation().getDirection().multiply(0.4));
        off.entity.getWorld().playEffect(hitLoc, Effect.MOBSPAWNER_FLAMES, 0);
        off.entity.getWorld().playEffect(hitLoc, Effect.VILLAGER_THUNDERCLOUD, 0);
        off.entity.getWorld().playEffect(hitLoc, Effect.CRIT, 0);

        // Sonidos de enfado y choque de porra
        off.entity.getWorld().playSound(off.entity.getLocation(), Sound.IRONGOLEM_THROW, 1.3f, 0.9f);
        off.entity.getWorld().playSound(off.entity.getLocation(), Sound.ANVIL_LAND, 0.8f, 1.6f);

        // Fijar objetivo para persecución activa táctica
        off.targetPlayer = attacker;
        off.pursuitTicks = 80; // 40 segundos de alerta y persecución activa

        if (hits == 1) {
            // 1er GOLPE EN LA CARA: CONTRAATAQUE DEFENSIVO INMEDIATO
            sendTitle(attacker,
                    ChatColor.BLUE + "" + ChatColor.BOLD + "🚔 ¡ALTO A LA POLICÍA!",
                    ChatColor.YELLOW + "¡No agredas al " + off.name + " en la cara!");

            attacker.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + off.name + ": " + ChatColor.RED + "¡¿Qué haces pegándome en la cara, " + attacker.getName() + "?! " + ChatColor.YELLOW + "¡Atrás inmediatamente! ¡Un golpe más y quedas detenido y desarmado!");

            // Empujón de contención
            Vector push = attacker.getLocation().toVector().subtract(off.entity.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 0.01) {
                push.normalize().multiply(1.10).setY(0.32);
                attacker.setVelocity(push);
            }

            // Descarga de porra / taser de advertencia
            attacker.damage(4.0, off.entity);
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 1)); // Slowness II por 5s
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0)); // Ceguera 1.5s
            attacker.playSound(attacker.getLocation(), Sound.ITEM_BREAK, 1.2f, 1.5f);

            if (plugin.getHonorManager() != null) {
                plugin.getHonorManager().addHonor(attacker, -10, "Golpear en la cara al " + off.name);
            }
            return;
        }

        if (hits == 2) {
            // 2º GOLPE EN LA CARA (CASO P_DIDDY): DETENCIÓN, DESARME TÁCTICO Y ESPOSADO
            sendTitle(attacker,
                    ChatColor.RED + "" + ChatColor.BOLD + "🚨 ¡DETENIDO Y REDUCIDO!",
                    ChatColor.YELLOW + "¡Agresión directa a la autoridad policial!");

            // Rayo cosmético espectacular sin fuego
            attacker.getWorld().spigot().strikeLightningEffect(attacker.getLocation(), false);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.EXPLODE, 0.8f, 1.8f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_BREAK, 1.2f, 0.7f);

            // DESARME TÁCTICO: Tira al suelo el arma o herramienta que lleva en la mano
            ItemStack inHand = attacker.getItemInHand();
            if (inHand != null && inHand.getType() != Material.AIR) {
                attacker.getWorld().dropItemNaturally(attacker.getLocation(), inHand.clone());
                attacker.setItemInHand(new ItemStack(Material.AIR));
                attacker.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "⚡ ¡EL POLICÍA TE HA DESARMADO DE UN PORRAZO EN LA MANO!");
            }

            // ESPOSADO E INMOVILIZACIÓN TÁCTICA
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 200, 3)); // Slowness IV por 10s (casi inmóvil)
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 2)); // Weakness III por 10s (sin fuerza)
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0)); // Ceguera por 2.5s

            // Fuerte derribo hacia atrás
            Vector push = attacker.getLocation().toVector().subtract(off.entity.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 0.01) {
                push.normalize().multiply(1.40).setY(0.38);
                attacker.setVelocity(push);
            }

            // Daño contundente respetando no-letalidad
            if (attacker.getHealth() > 6.0) {
                attacker.damage(6.0, off.entity);
            } else if (attacker.getHealth() > 2.0) {
                attacker.damage(attacker.getHealth() - 2.0, off.entity);
            }

            // Anuncio público a todo el servidor
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");
            Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "🚨 [POLICÍA NACIONAL] ¡" + attacker.getName() + " ha sido REDUCIDO y ESPOSADO por golpear en la cara al " + off.name + "!");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "  El sujeto ha sido desarmado e inmovilizado bajo custodia policial.");
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");

            plugin.addWarning(attacker.getName(), "Agredir al " + off.name + " en la cara (2 golpes)", "Policía");

            if (plugin.getHonorManager() != null) {
                plugin.getHonorManager().addHonor(attacker, -25, "Agresión directa en la cara a un policía");
            }
            return;
        }

        // 3 GOLPES O MÁS: AGRESIÓN REITERADA EXTREMA
        sendTitle(attacker,
                ChatColor.DARK_RED + "" + ChatColor.BOLD + "🚨 ¡FRACTURA Y NEUTRALIZACIÓN!",
                ChatColor.RED + "¡Desacato extremo a la autoridad!");

        attacker.getWorld().spigot().strikeLightningEffect(attacker.getLocation(), false);
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 300, 4));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 300, 3));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));

        if (attacker.getHealth() > 4.0) {
            attacker.damage(attacker.getHealth() - 2.0, off.entity);
        }

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "🚨 [POLICÍA NACIONAL] ¡Agresión reiterada de " + attacker.getName() + " neutralizada con la máxima fuerza reglamentaria!");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");

        applyPoliceSanction(off, attacker, "Agresión reiterada y continuada en la cara a un agente");
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
        if (culprit == null || !culprit.isOnline()) return;
        if (culprit.getName().equalsIgnoreCase(plugin.getOwnerName())) return;

        PoliceOfficer witnessingOfficer = null;
        double minDist = Double.MAX_VALUE;

        // Detección física directa por visión (35 bloques) O acústica inmediata por cercanía (30 bloques)
        for (PoliceOfficer off : officers.values()) {
            if (off.isAlive() && !off.killedThisSession && off.entity != null && off.entity.getWorld().equals(crimeLocation.getWorld())) {
                double dist = off.entity.getLocation().distance(crimeLocation);
                boolean visible = (dist <= 35.0 && off.canSee(crimeLocation));
                boolean audible = (dist <= 30.0); // Ruidos fuertes: rotura de estructuras, picos, alarmas
                if ((visible || audible) && dist < minDist) {
                    minDist = dist;
                    witnessingOfficer = off;
                }
            }
        }

        if (witnessingOfficer == null) return; // Ningún policía estaba cerca para presenciarlo

        witnessingOfficer.entity.getWorld().playSound(witnessingOfficer.entity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        witnessingOfficer.entity.getWorld().playSound(witnessingOfficer.entity.getLocation(), Sound.IRONGOLEM_THROW, 1.0f, 1.2f);

        Vector dir = crimeLocation.toVector().subtract(witnessingOfficer.entity.getLocation().toVector());
        witnessingOfficer.entity.teleport(witnessingOfficer.entity.getLocation().setDirection(dir));

        String locDesc = (witnessingOfficer.activeSector != null ? witnessingOfficer.activeSector.name : "Zona Vigilada");

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + witnessingOfficer.name + ": " + ChatColor.RED + "¡ALTO EN NOMBRE DE LA LEY, " + ChatColor.YELLOW + culprit.getName().toUpperCase() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "  Infracción presenciada en " + ChatColor.AQUA + locDesc + ChatColor.YELLOW + ": " + ChatColor.WHITE + crimeTitle + " (" + description + ")");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        // Actuación física inmediata y severa: intervención táctica
        final PoliceOfficer cop = witnessingOfficer;
        cop.targetPlayer = culprit;
        cop.pursuitTicks = 40; // 20 segundos de persecución/vigilancia activa sobre el sospechoso

        try {
            if (cop.entity instanceof CraftCreature) {
                ((CraftCreature) cop.entity).getHandle().getNavigation().a(culprit.getLocation().getX(), culprit.getLocation().getY(), culprit.getLocation().getZ(), 1.30);
            }
        } catch (Exception ignored) {}

        // Desarme táctico si porta herramientas o armas con las que comete el delito
        ItemStack inHand = culprit.getItemInHand();
        if (inHand != null && inHand.getType() != Material.AIR) {
            culprit.getWorld().dropItemNaturally(culprit.getLocation(), inHand.clone());
            culprit.setItemInHand(new ItemStack(Material.AIR));
            culprit.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "⚡ ¡LA POLICÍA TE HA DESARMADO POR COMETER UN DELITO!");
        }

        // Efecto de contención (frena la huida)
        culprit.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 80, 1));

        final String finalCrime = crimeTitle;
        if (cop.entity.getWorld().equals(culprit.getWorld())) {
            double distToCulprit = cop.entity.getLocation().distance(culprit.getLocation());
            if (distToCulprit <= 4.0) {
                deliverBatonStrike(cop, culprit, "Infracción presenciada: " + crimeTitle);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (cop.isAlive() && culprit.isOnline() && cop.entity != null && cop.entity.getWorld().equals(culprit.getWorld())) {
                            if (cop.entity.getLocation().distance(culprit.getLocation()) <= 4.0) {
                                deliverBatonStrike(cop, culprit, "Infracción presenciada: " + finalCrime);
                            }
                        }
                    }
                }, 14L);
            }
        }

        applyPoliceSanction(witnessingOfficer, culprit, crimeTitle);
    }

    public void witnessPvP(Player attacker, Player victim, Location crimeLocation) {
        if (attacker == null || victim == null || !attacker.isOnline() || !victim.isOnline()) return;
        if (attacker.equals(victim)) return;
        if (attacker.getName().equalsIgnoreCase(plugin.getOwnerName())) return;

        PoliceOfficer witnessingOfficer = null;
        double minDist = Double.MAX_VALUE;

        // Detección física directa o acústica por gritos y golpes
        for (PoliceOfficer off : officers.values()) {
            if (off.isAlive() && !off.killedThisSession && off.entity != null && off.entity.getWorld().equals(crimeLocation.getWorld())) {
                double dist = off.entity.getLocation().distance(crimeLocation);
                boolean visible = (dist <= 35.0 && off.canSee(crimeLocation));
                boolean audible = (dist <= 30.0); // Oye la agresión y golpes cercanos
                if ((visible || audible) && dist < minDist) {
                    minDist = dist;
                    witnessingOfficer = off;
                }
            }
        }

        if (witnessingOfficer == null) return;

        final PoliceOfficer cop = witnessingOfficer;
        cop.targetPlayer = attacker;
        cop.pursuitTicks = 50; // 25 segundos de persecución activa al agresor

        cop.entity.getWorld().playSound(cop.entity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        cop.entity.getWorld().playSound(cop.entity.getLocation(), Sound.IRONGOLEM_THROW, 1.0f, 1.2f);

        Vector dir = crimeLocation.toVector().subtract(cop.entity.getLocation().toVector());
        cop.entity.teleport(cop.entity.getLocation().setDirection(dir));

        try {
            if (cop.entity instanceof CraftCreature) {
                ((CraftCreature) cop.entity).getHandle().getNavigation().a(
                        attacker.getLocation().getX(), attacker.getLocation().getY(), attacker.getLocation().getZ(), 1.35);
            }
        } catch (Exception ignored) {}

        // Desarme táctico fulminante del agresor
        ItemStack inHand = attacker.getItemInHand();
        if (inHand != null && inHand.getType() != Material.AIR) {
            attacker.getWorld().dropItemNaturally(attacker.getLocation(), inHand.clone());
            attacker.setItemInHand(new ItemStack(Material.AIR));
            attacker.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "⚡ ¡LA POLICÍA TE HA DESARMADO DE INMEDIATO POR AGRESIÓN!");
        }

        // Neutralización para que no pueda seguir pegando a la víctima
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 140, 2));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 140, 2));

        // Empujar al agresor lejos de la víctima
        Vector push = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (push.lengthSquared() > 0.01) {
            push.normalize().multiply(1.25).setY(0.28);
            attacker.setVelocity(push);
        }

        String locDesc = (cop.activeSector != null ? cop.activeSector.name : "Vía Pública");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] " + cop.name + ": " + ChatColor.RED + "¡ALTO AL FUEGO! ¡ALTO EN NOMBRE DE LA LEY!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "  Agresión de " + ChatColor.RED + attacker.getName() + ChatColor.YELLOW + " contra " + ChatColor.GREEN + victim.getName() + ChatColor.YELLOW + " intervenida en " + ChatColor.AQUA + locDesc + ChatColor.YELLOW + ".");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        deliverBatonStrike(cop, attacker, "Agresión física ilegal contra " + victim.getName());
        applyPoliceSanction(cop, attacker, "Agresión física a ciudadano (" + victim.getName() + ")");
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
            culprit.sendMessage(ChatColor.GOLD + "🚔 [POLICÍA] Aviso informativo (1ª advertencia): Por favor respeta las normas de convivencia. No pasa nada grave, pero evitemos incidentes.");
        } else if (warnCount == 2) {
            culprit.sendMessage(ChatColor.GOLD + "🚔 [POLICÍA] 2ª advertencia: Te pedimos colaboración, por favor evita reincidir para no ser expulsado del servidor.");
            culprit.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 0));
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
            if (dest != null) {
                if (!dest.getChunk().isLoaded()) dest.getChunk().load(true);
                if (!off.entity.getLocation().getChunk().isLoaded()) off.entity.getLocation().getChunk().load(true);
                off.entity.teleport(dest);
                off.entity.getWorld().playSound(dest, Sound.NOTE_PLING, 1.2f, 1.8f);
                plugin.getLogger().info("[PoliceManager] " + off.name + " desplazado a patrullar: " + targetSector.name);
            }
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

    public void checkAndManagePoliceDoors() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        long now = System.currentTimeMillis();

        // 1. Detección y apertura automática de puertas frente a cada policía activo
        for (PoliceOfficer off : officers.values()) {
            if (off.killedThisSession || off.entity == null || off.entity.isDead()) continue;
            if (!off.isChunkLoaded()) continue;

            Location pLoc = off.entity.getLocation();
            World w = pLoc.getWorld();
            int px = pLoc.getBlockX();
            int py = pLoc.getBlockY();
            int pz = pLoc.getBlockZ();

            // Escanear área inmediata en un radio de 1.8 bloques
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block b = w.getBlockAt(px + dx, py + dy, pz + dz);
                        if (isDoorBlock(b)) {
                            Block master = getMasterDoorBlock(b);
                            if (!isDoorOpen(master)) {
                                openDoor(master);
                            }
                            // Mantener la puerta abierta mientras el agente esté cerca + 3.5 segundos tras cruzar
                            activePoliceDoors.put(master.getLocation(), now + 3500L);
                        }
                    }
                }
            }
        }

        // 2. Cierre educado de puertas tras cruzar (no dejar puertas abiertas)
        if (!activePoliceDoors.isEmpty()) {
            List<Location> toClose = new ArrayList<Location>();
            for (Map.Entry<Location, Long> entry : activePoliceDoors.entrySet()) {
                Location loc = entry.getKey();
                if (now >= entry.getValue()) {
                    boolean occupied = false;
                    World w = loc.getWorld();
                    if (w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                        for (Entity e : w.getNearbyEntities(loc, 1.8, 1.8, 1.8)) {
                            if (e instanceof Player || isPolice(e)) {
                                occupied = true;
                                break;
                            }
                        }
                    }
                    if (occupied) {
                        entry.setValue(now + 1500L);
                    } else {
                        toClose.add(loc);
                    }
                }
            }

            for (Location loc : toClose) {
                activePoliceDoors.remove(loc);
                Block b = loc.getBlock();
                if (isDoorBlock(b) && isDoorOpen(b)) {
                    closeDoor(b);
                }
            }
        }
    }

    public static boolean isDoorBlock(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        return m == Material.WOODEN_DOOR || m == Material.IRON_DOOR_BLOCK
                || m == Material.SPRUCE_DOOR || m == Material.BIRCH_DOOR
                || m == Material.JUNGLE_DOOR || m == Material.ACACIA_DOOR
                || m == Material.DARK_OAK_DOOR || m == Material.TRAP_DOOR
                || m == Material.IRON_TRAPDOOR || m == Material.FENCE_GATE
                || m == Material.SPRUCE_FENCE_GATE || m == Material.BIRCH_FENCE_GATE
                || m == Material.JUNGLE_FENCE_GATE || m == Material.DARK_OAK_FENCE_GATE
                || m == Material.ACACIA_FENCE_GATE;
    }

    public static Block getMasterDoorBlock(Block b) {
        if (b == null) return null;
        Material m = b.getType();
        if (m == Material.WOODEN_DOOR || m == Material.IRON_DOOR_BLOCK
                || m == Material.SPRUCE_DOOR || m == Material.BIRCH_DOOR
                || m == Material.JUNGLE_DOOR || m == Material.ACACIA_DOOR
                || m == Material.DARK_OAK_DOOR) {
            byte data = b.getData();
            if ((data & 0x8) != 0) {
                return b.getRelative(BlockFace.DOWN);
            }
        }
        return b;
    }

    public static boolean isDoorOpen(Block b) {
        if (b == null) return false;
        Block master = getMasterDoorBlock(b);
        return (master.getData() & 0x4) != 0;
    }

    public static void openDoor(Block b) {
        if (b == null) return;
        Block master = getMasterDoorBlock(b);
        byte current = master.getData();
        if ((current & 0x4) == 0) {
            master.setData((byte) (current | 0x4), true);
            master.getWorld().playEffect(master.getLocation(), Effect.DOOR_TOGGLE, 0);
        }
    }

    public static void closeDoor(Block b) {
        if (b == null) return;
        Block master = getMasterDoorBlock(b);
        byte current = master.getData();
        if ((current & 0x4) != 0) {
            master.setData((byte) (current & ~0x4), true);
            master.getWorld().playEffect(master.getLocation(), Effect.DOOR_TOGGLE, 0);
        }
    }
}
