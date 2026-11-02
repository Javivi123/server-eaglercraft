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
            // Evitar duplicados muy cercanos
            for (Location existing : waypoints) {
                if (existing.distanceSquared(loc) < 3.0 * 3.0) return;
            }
            waypoints.add(loc);
        }
    }

    private final XaviAdminProtect plugin;
    private LivingEntity policeEntity = null;
    private boolean killedThisSession = false;
    private int patrolTaskId = -1;
    private final Random random = new Random();

    // Gestión dinámica de sectores de patrullaje (superficie y subterráneos)
    private final Map<String, PatrolSector> sectors = new LinkedHashMap<String, PatrolSector>();
    private PatrolSector activeSector = null;
    private int currentSectorIndex = 0;
    private int currentWaypointIndex = 0;
    private int ticksInCurrentSector = 0;

    // Control de sospechas e investigaciones
    private int suspicionCooldown = 0;

    public PoliceManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        rebuildPatrolSectors();
        startPatrolLoop();
    }

    public boolean isKilledThisSession() {
        return killedThisSession;
    }

    public void setKilledThisSession(boolean killed) {
        this.killedThisSession = killed;
    }

    public LivingEntity getPoliceEntity() {
        if (policeEntity != null && policeEntity.isValid() && !policeEntity.isDead()) {
            return policeEntity;
        }
        return null;
    }

    public boolean isPolice(Entity e) {
        if (e == null) return false;
        if (policeEntity != null && e.getUniqueId().equals(policeEntity.getUniqueId())) {
            return true;
        }
        if (e instanceof LivingEntity) {
            String name = ((LivingEntity) e).getCustomName();
            if (name != null && name.contains("POLICÍA")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escanea y reconstruye dinámicamente todos los sectores con construcciones no naturales
     * de jugadores, tanto en superficie como subterráneas (Y < 63).
     */
    public synchronized void rebuildPatrolSectors() {
        sectors.clear();
        World w = Bukkit.getWorld("world");
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        if (w == null) return;

        // 1. Sector Central: Casa de Xavi y Plaza Central (Superficie)
        PatrolSector central = new PatrolSector("central", "Plaza Central y Casa de Xavi [Superficie]", w.getName(), false);
        central.addWaypoint(new Location(w, -272.5, 77.0, -6.5));
        central.addWaypoint(new Location(w, -285.0, 77.0, -8.0));
        central.addWaypoint(new Location(w, -275.0, 76.0, 5.0));
        central.addWaypoint(new Location(w, -264.0, 76.0, -2.0));
        sectors.put(central.id, central);

        // 2. Sector Oeste: Barrio Residencial Oeste (Superficie, taetti y Leo)
        PatrolSector oeste = new PatrolSector("oeste", "Barrio Residencial Oeste [Superficie]", w.getName(), false);
        oeste.addWaypoint(new Location(w, -363.5, 96.0, -48.5));
        oeste.addWaypoint(new Location(w, -366.5, 96.0, -44.5));
        oeste.addWaypoint(new Location(w, -360.5, 96.0, -48.5));
        sectors.put(oeste.id, oeste);

        // 3. Sector Subterráneo 1: Galería y Catacumbas P_Diddy & taetti (Y=61..65)
        PatrolSector sub1 = new PatrolSector("sub_galeria", "Galería Subterránea P_Diddy & taetti [Subterráneo]", w.getName(), true);
        sub1.addWaypoint(new Location(w, -255.5, 65.0, -13.5));
        sub1.addWaypoint(new Location(w, -252.5, 61.0, -25.5));
        sub1.addWaypoint(new Location(w, -370.5, 61.0, -35.5));
        sectors.put(sub1.id, sub1);

        // 4. Sector Este: Nueva Colonia Piccino y Leo (Superficie)
        PatrolSector este = new PatrolSector("este", "Nueva Colonia Este Piccino & Leo [Superficie]", w.getName(), false);
        este.addWaypoint(new Location(w, -11.5, 76.0, -80.5));
        este.addWaypoint(new Location(w, -6.5, 76.0, -80.5));
        este.addWaypoint(new Location(w, -12.5, 77.0, -80.5));
        sectors.put(este.id, este);

        // 5. Sector Subterráneo 2: Minas Profundas de Diamantes (Y=11..19 Piccino, taetti, Leo)
        PatrolSector subDiamantes = new PatrolSector("sub_minas_diamantes", "Minas Profundas Diamantes [Subterráneo Y=11-19]", w.getName(), true);
        subDiamantes.addWaypoint(new Location(w, -32.5, 11.0, -69.5));
        subDiamantes.addWaypoint(new Location(w, -16.5, 19.0, -137.5));
        sectors.put(subDiamantes.id, subDiamantes);

        // 6. Sector Subterráneo 3: Red de Minas Antiguas (Y=27..47 Xavi, Piccino, taetti)
        PatrolSector subAntiguo = new PatrolSector("sub_minas_antiguas", "Red de Minas y Búnker Sur [Subterráneo Y=27-47]", w.getName(), true);
        subAntiguo.addWaypoint(new Location(w, -787.5, 35.0, -140.5));
        subAntiguo.addWaypoint(new Location(w, -799.5, 47.0, -187.5));
        subAntiguo.addWaypoint(new Location(w, -752.5, 27.0, -182.5));
        sectors.put(subAntiguo.id, subAntiguo);

        // 7. Sector Avanzada Lejana taetti
        PatrolSector lejana = new PatrolSector("avanzada_lejana", "Avanzada Lejana [Superficie]", w.getName(), false);
        lejana.addWaypoint(new Location(w, -819.5, 67.0, -720.5));
        lejana.addWaypoint(new Location(w, -1007.5, 65.0, -515.5));
        sectors.put(lejana.id, lejana);

        // Incorporar todos los cofres registrados en ChestManager automáticamente
        if (plugin.getChestManager() != null) {
            for (ChestManager.ChestData cd : plugin.getChestManager().getAllChests().values()) {
                boolean isUnder = cd.y < 63;
                Location cLoc = new Location(w, cd.x + 0.5, cd.y, cd.z + 0.5);

                // Encontrar el sector más cercano o crear uno
                PatrolSector match = null;
                for (PatrolSector sec : sectors.values()) {
                    if (sec.isUnderground == isUnder) {
                        for (Location wp : sec.waypoints) {
                            if (wp.distanceSquared(cLoc) < 45.0 * 45.0) {
                                match = sec;
                                break;
                            }
                        }
                    }
                    if (match != null) break;
                }

                if (match != null) {
                    match.addWaypoint(cLoc);
                } else {
                    String sId = (isUnder ? "sub_sector_" : "urb_sector_") + cd.x + "_" + cd.z;
                    String sName = (isUnder ? "Galería Subterránea [" : "Puesto Construido [") + cd.x + ", " + cd.z + "]";
                    PatrolSector dynamicSec = new PatrolSector(sId, sName, w.getName(), isUnder);
                    dynamicSec.addWaypoint(cLoc);
                    sectors.put(sId, dynamicSec);
                }
            }
        }

        if (activeSector == null || !sectors.containsKey(activeSector.id)) {
            activeSector = central;
        }

        plugin.getLogger().info("[PoliceManager] Inicializados " + sectors.size() + " sectores de patrullaje policial (Superficiales y Subterráneos).");
    }

    /**
     * Registra dinámicamente una nueva construcción de jugador en el radar de patrullaje
     */
    public void registerPlayerConstruction(Location loc) {
        if (loc == null) return;
        boolean isUnder = loc.getBlockY() < 63;
        for (PatrolSector sec : sectors.values()) {
            if (sec.isUnderground == isUnder) {
                for (Location wp : sec.waypoints) {
                    if (wp.distanceSquared(loc) < 35.0 * 35.0) {
                        sec.addWaypoint(loc.clone().add(0.5, 0.0, 0.5));
                        return;
                    }
                }
            }
        }
        // Crear nuevo sub-sector si está lejos
        String id = (isUnder ? "sub_extra_" : "urb_extra_") + loc.getBlockX() + "_" + loc.getBlockZ();
        String name = (isUnder ? "Sector Subterráneo " : "Sector Construido ") + "[" + loc.getBlockX() + ", " + loc.getBlockZ() + "]";
        PatrolSector extra = new PatrolSector(id, name, loc.getWorld().getName(), isUnder);
        extra.addWaypoint(loc.clone().add(0.5, 0.0, 0.5));
        sectors.put(id, extra);
    }

    public synchronized void spawnPolice() {
        if (killedThisSession) {
            plugin.getLogger().info("[PoliceManager] El policía fue abatido en esta sesión. Permanece fuera de servicio hasta reiniciar el servidor.");
            return;
        }

        if (policeEntity != null && policeEntity.isValid() && !policeEntity.isDead()) {
            return;
        }

        World w = Bukkit.getWorld("world");
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        if (w == null) return;

        // Limpiar duplicados si hubiese
        for (Entity e : w.getEntities()) {
            if (e instanceof LivingEntity && !(e instanceof Player)) {
                String cName = ((LivingEntity) e).getCustomName();
                if (cName != null && cName.contains("POLICÍA")) {
                    e.remove();
                }
            }
        }

        Location spawnLoc = (activeSector != null && !activeSector.waypoints.isEmpty())
                ? activeSector.waypoints.get(0).clone()
                : new Location(w, -272.5, 77.0, -6.5, 0f, 0f);

        // Asegurar piso sólido
        spawnLoc = findSafeStandingLocation(spawnLoc);

        Zombie cop = (Zombie) w.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        cop.setBaby(false);
        cop.setVillager(false);
        cop.setCanPickupItems(false);
        cop.setRemoveWhenFarAway(false);

        cop.setCustomName(ChatColor.BLUE + "" + ChatColor.BOLD + "🚔 [POLICÍA] Agente Torres");
        cop.setCustomNameVisible(true);

        cop.setMaxHealth(60.0);
        cop.setHealth(60.0);

        // Equipamiento policial reglamentario
        EntityEquipment eq = cop.getEquipment();

        ItemStack helmet = new ItemStack(Material.CHAINMAIL_HELMET);
        ItemMeta hMeta = helmet.getItemMeta();
        hMeta.setDisplayName(ChatColor.BLUE + "Gorra Reglamentaria de Policía");
        helmet.setItemMeta(hMeta);
        eq.setHelmet(helmet);
        eq.setHelmetDropChance(0.0f);

        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta cMeta = (LeatherArmorMeta) chest.getItemMeta();
        cMeta.setColor(Color.fromRGB(16, 44, 87));
        cMeta.setDisplayName(ChatColor.BLUE + "Chaleco Oficial de Policía");
        chest.setItemMeta(cMeta);
        eq.setChestplate(chest);
        eq.setChestplateDropChance(0.0f);

        ItemStack legs = new ItemStack(Material.LEATHER_LEGGINGS);
        LeatherArmorMeta lMeta = (LeatherArmorMeta) legs.getItemMeta();
        lMeta.setColor(Color.fromRGB(20, 25, 45));
        lMeta.setDisplayName(ChatColor.BLUE + "Pantalón Táctico Oficial");
        legs.setItemMeta(lMeta);
        eq.setLeggings(legs);
        eq.setLeggingsDropChance(0.0f);

        ItemStack boots = new ItemStack(Material.IRON_BOOTS);
        ItemMeta bMeta = boots.getItemMeta();
        bMeta.setDisplayName(ChatColor.BLUE + "Botas Policiales Reforzadas");
        boots.setItemMeta(bMeta);
        eq.setBoots(boots);
        eq.setBootsDropChance(0.0f);

        ItemStack baton = new ItemStack(Material.IRON_SWORD);
        ItemMeta batonMeta = baton.getItemMeta();
        batonMeta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Porra Policial Reglamentaria");
        baton.setItemMeta(batonMeta);
        eq.setItemInHand(baton);
        eq.setItemInHandDropChance(0.0f);

        cop.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 999999, 1, false, false));
        cop.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 0, false, false));

        this.policeEntity = cop;
        plugin.getLogger().info("[PoliceManager] Agente Torres desplegado en servicio en " + spawnLoc.getBlockX() + ", " + spawnLoc.getBlockY() + ", " + spawnLoc.getBlockZ() + " (" + (activeSector != null ? activeSector.name : "Sector Central") + ")");
    }

    public void removePolice() {
        if (policeEntity != null && policeEntity.isValid()) {
            policeEntity.remove();
        }
        policeEntity = null;
    }

    private void startPatrolLoop() {
        if (patrolTaskId != -1) {
            Bukkit.getScheduler().cancelTask(patrolTaskId);
        }

        patrolTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (Bukkit.getOnlinePlayers().isEmpty()) return;
                if (killedThisSession) return;

                if (policeEntity == null || !policeEntity.isValid() || policeEntity.isDead()) {
                    spawnPolice();
                    return;
                }

                // Evitar ataques automáticos a inocentes
                if (policeEntity instanceof Creature) {
                    Creature cr = (Creature) policeEntity;
                    if (cr.getTarget() instanceof Player) {
                        Player tp = (Player) cr.getTarget();
                        if (!tp.isOnline() || tp.isDead()) {
                            cr.setTarget(null);
                        }
                    }
                }

                ticksInCurrentSector += 8;
                if (suspicionCooldown > 0) suspicionCooldown -= 8;

                // Evaluación inteligente: Si un jugador está activo en otro sector, priorizar vigilarlo
                evaluateSectorRotation();
                patrolNextStep();
            }
        }, 80L, 120L); // Cada 6 segundos procesa un ciclo de inteligencia y patrullaje
    }

    /**
     * Sistema Inteligente de Rotación de Sectores:
     * Si hay jugadores en una zona subterránea o en otro sector construido, el policía se
     * traslada tácticamente a patrullar allí para que ninguna construcción quede desprotegida.
     */
    private void evaluateSectorRotation() {
        if (sectors.isEmpty() || policeEntity == null) return;

        // 1. Detectar si algún jugador está en un sector distinto al actual
        PatrolSector sectorWithPlayer = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location pLoc = p.getLocation();
            for (PatrolSector sec : sectors.values()) {
                boolean matchUnder = sec.isUnderground == (pLoc.getBlockY() < 63);
                if (matchUnder) {
                    for (Location wp : sec.waypoints) {
                        if (wp.getWorld().equals(pLoc.getWorld()) && wp.distanceSquared(pLoc) < 32.0 * 32.0) {
                            sectorWithPlayer = sec;
                            break;
                        }
                    }
                }
                if (sectorWithPlayer != null) break;
            }
            if (sectorWithPlayer != null) break;
        }

        // Si hay jugadores en otro sector y llevamos más de 30 segundos en el actual:
        if (sectorWithPlayer != null && !sectorWithPlayer.id.equals(activeSector.id) && ticksInCurrentSector >= 30) {
            dispatchToSector(sectorWithPlayer);
            return;
        }

        // Si no hay jugadores específicos, rotar periódicamente cada 60 segundos por todos los sectores
        if (ticksInCurrentSector >= 60) {
            List<PatrolSector> secList = new ArrayList<PatrolSector>(sectors.values());
            currentSectorIndex = (currentSectorIndex + 1) % secList.size();
            dispatchToSector(secList.get(currentSectorIndex));
        }
    }

    public void dispatchToSector(PatrolSector targetSector) {
        if (targetSector == null || policeEntity == null) return;
        this.activeSector = targetSector;
        this.ticksInCurrentSector = 0;
        this.currentWaypointIndex = 0;

        if (targetSector.waypoints.isEmpty()) return;
        Location dest = targetSector.waypoints.get(0);
        dest = findSafeStandingLocation(dest);

        double distSq = policeEntity.getLocation().distanceSquared(dest);

        // Si está a más de 35 bloques o en nivel subterráneo diferente, desplazamiento táctico
        if (distSq > 35.0 * 35.0 || Math.abs(policeEntity.getLocation().getBlockY() - dest.getBlockY()) > 10) {
            policeEntity.teleport(dest);
            policeEntity.getWorld().playSound(dest, Sound.NOTE_PLING, 1.2f, 1.8f);
            policeEntity.getWorld().playSound(dest, Sound.BAT_TAKEOFF, 0.8f, 1.2f);

            // Avisar sutilmente en consola y a jugadores cercanos de la ronda
            plugin.getLogger().info("[PoliceManager] Agente Torres desplazado a patrullar: " + targetSector.name);
            for (Entity e : policeEntity.getNearbyEntities(20, 10, 20)) {
                if (e instanceof Player) {
                    ((Player) e).sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.GRAY + "Iniciando ronda de vigilancia en " + targetSector.name + ".");
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

        // Buscar bloque de suelo sólido y 2 bloques de aire arriba
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

    /**
     * Paso inteligente de patrullaje:
     * Inspecciona comportamientos sospechosos (sigilo junto a cofres, armas desenfundadas)
     * y recorre los waypoints del sector activo.
     */
    private void patrolNextStep() {
        if (policeEntity == null || !policeEntity.isValid()) return;
        if (activeSector == null || activeSector.waypoints.isEmpty()) return;

        Location pLoc = policeEntity.getLocation();

        // 1. ANÁLISIS DE SOSPECHAS: Inspeccionar jugadores en el sector
        Player suspect = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(pLoc.getWorld())) {
                double dist = p.getLocation().distance(pLoc);
                if (dist < 20.0 && canPoliceSee(p.getEyeLocation())) {
                    // ¿Jugador agachado/sigiloso cerca de propiedades o cofres?
                    if (p.isSneaking()) {
                        suspect = p;
                        break;
                    }
                    // ¿Jugador con espada/hacha/arco en mano dentro de zona habitada?
                    ItemStack hand = p.getItemInHand();
                    if (hand != null && (hand.getType().name().contains("SWORD") || hand.getType().name().contains("AXE") || hand.getType() == Material.BOW)) {
                        if (dist < 10.0 && random.nextDouble() < 0.20 && suspicionCooldown <= 0) {
                            p.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.YELLOW + "Por favor " + p.getName() + ", envaina tu arma mientras estés en zona habitada. Evitemos incidentes.");
                            p.playSound(p.getLocation(), Sound.NOTE_PLING, 1.0f, 2.0f);
                            suspicionCooldown = 20;
                        }
                    }
                }
            }
        }

        // Si hay alguien en sigilo sospechoso, el policía se acerca a investigarlo de cerca
        if (suspect != null && suspicionCooldown <= 0) {
            Location sLoc = suspect.getLocation();
            Vector toSuspect = sLoc.toVector().subtract(pLoc.toVector()).normalize().multiply(3.5);
            double destX = sLoc.getX() - toSuspect.getX();
            double destY = sLoc.getY();
            double destZ = sLoc.getZ() - toSuspect.getZ();

            try {
                if (policeEntity instanceof CraftCreature) {
                    ((CraftCreature) policeEntity).getHandle().getNavigation().a(destX, destY, destZ, 1.15);
                }
            } catch (Exception ignored) {}

            suspect.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.YELLOW + "¿Qué haces merodeando en sigilo por aquí, " + suspect.getName() + "? Mantén la compostura.");
            suspect.playSound(suspect.getLocation(), Sound.NOTE_PLING, 1.0f, 2.0f);
            suspicionCooldown = 18;
            return;
        }

        // 2. PATRULLAJE NORMAL POR WAYPOINTS DEL SECTOR
        Location targetWp = activeSector.waypoints.get(currentWaypointIndex);
        currentWaypointIndex = (currentWaypointIndex + 1) % activeSector.waypoints.size();

        try {
            if (policeEntity instanceof CraftCreature) {
                ((CraftCreature) policeEntity).getHandle().getNavigation().a(targetWp.getX(), targetWp.getY(), targetWp.getZ(), 1.05);
            }
        } catch (Exception ignored) {}

        // 10% probabilidad de emitir informe de rutina si hay jugadores cerca
        if (random.nextDouble() < 0.10) {
            for (Entity e : policeEntity.getNearbyEntities(14, 6, 14)) {
                if (e instanceof Player) {
                    ((Player) e).sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.GRAY + "Patrullando " + activeSector.name + ". Sin novedades que reportar.");
                    break;
                }
            }
        }
    }

    /**
     * Comprobación física estricta de Campo de Visión (FOV) y Raycast sin muros de por medio
     */
    public boolean canPoliceSee(Location targetLoc) {
        if (policeEntity == null || !policeEntity.isValid() || policeEntity.isDead()) {
            return false;
        }
        Location eye = policeEntity.getEyeLocation();
        if (!eye.getWorld().equals(targetLoc.getWorld())) {
            return false;
        }

        double distSq = eye.distanceSquared(targetLoc);
        if (distSq > 28.0 * 28.0) return false;

        // Cono de visión frontal (~150° horizontales)
        Vector policeDir = eye.getDirection().setY(0).normalize();
        Vector toTarget = targetLoc.clone().subtract(eye).toVector().setY(0).normalize();
        double dot = policeDir.dot(toTarget);

        if (dot < 0.22) {
            return false; // Está a su espalda o fuera de su visión
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
            if (b != null) {
                Material m = b.getType();
                if (isVisionBlocking(m)) {
                    return false; // Muro u obstáculo opaco en la línea de visión
                }
            }
        }

        return true;
    }

    private boolean isVisionBlocking(Material m) {
        if (m == null || m == Material.AIR) return false;
        if (m == Material.GLASS || m == Material.THIN_GLASS || m == Material.STAINED_GLASS || m == Material.STAINED_GLASS_PANE) return false;
        if (m == Material.FENCE || m == Material.FENCE_GATE || m == Material.IRON_FENCE || m == Material.NETHER_FENCE) return false;
        if (m == Material.TORCH || m == Material.WATER || m == Material.STATIONARY_WATER) return false;
        if (m == Material.SIGN_POST || m == Material.WALL_SIGN || m == Material.CARPET || m == Material.LADDER) return false;
        return m.isOccluding() || m.isSolid();
    }

    /**
     * Ataque al policía: si es por la espalda no se entera ("no hace nada").
     * Si es de frente, reacciona y sanciona.
     */
    public void handleAttackOnPolice(Player attacker) {
        if (policeEntity == null || !policeEntity.isValid()) return;

        boolean sawAttacker = canPoliceSee(attacker.getEyeLocation());

        if (!sawAttacker) {
            // Ataque con sigilo por la espalda o desde punto ciego
            attacker.sendMessage(ChatColor.DARK_GRAY + "🗡 [Sigilo] Has golpeado al policía por la espalda sin que te vea. No se ha enterado de quién fue.");
            return;
        }

        // Ataque de frente visto por el policía
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.ANVIL_LAND, 0.8f, 1.5f);

        Vector toAttacker = attacker.getLocation().toVector().subtract(policeEntity.getLocation().toVector());
        Location faceLoc = policeEntity.getLocation().setDirection(toAttacker);
        policeEntity.teleport(faceLoc);

        if (policeEntity instanceof Creature) {
            ((Creature) policeEntity).setTarget(attacker);
        }

        // Taser / Aturdimiento
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 120, 2));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 1));

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.RED + "¡ALTO! ¡Agresión directa a un agente de la ley cometida por " + ChatColor.YELLOW + attacker.getName() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        applyPoliceSanction(attacker, "Agresión directa a un agente de policía en servicio");
    }

    public void handlePoliceDeath() {
        this.killedThisSession = true;
        this.policeEntity = null;

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "🚨 [POLICÍA NACIONAL] ¡El Agente de Policía ha caído en cumplimiento de su deber!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "La zona queda sin patrullaje policial durante el resto de la jornada.");
        Bukkit.broadcastMessage(ChatColor.GRAY + "(Volverá a estar activo cuando el servidor se apague y se vuelva a encender)");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "══════════════════════════════════════════════════");

        plugin.getLogger().warning("[PoliceManager] El policía ha sido asesinado. Permanece muerto hasta el próximo reinicio.");
    }

    public void witnessCrime(Player culprit, String crimeTitle, String description, Location crimeLocation) {
        if (policeEntity == null || !policeEntity.isValid() || policeEntity.isDead() || killedThisSession) {
            return;
        }

        if (!canPoliceSee(crimeLocation)) {
            return; // Fuera de campo de visión o tras paredes
        }

        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.IRONGOLEM_HIT, 1.0f, 1.0f);

        Vector dir = crimeLocation.toVector().subtract(policeEntity.getLocation().toVector());
        policeEntity.teleport(policeEntity.getLocation().setDirection(dir));

        String locDesc = (activeSector != null ? activeSector.name : "Zona Habitada");

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.RED + "¡ALTO EN NOMBRE DE LA LEY, " + ChatColor.YELLOW + culprit.getName().toUpperCase() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "  Infracción presenciada en " + ChatColor.AQUA + locDesc + ChatColor.YELLOW + ": " + ChatColor.WHITE + crimeTitle + " (" + description + ")");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        applyPoliceSanction(culprit, crimeTitle);
    }

    public void applyPoliceSanction(final Player culprit, String reason) {
        if (culprit == null || !culprit.isOnline()) return;

        if (culprit.getName().equalsIgnoreCase(plugin.getOwnerName())) {
            culprit.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.GREEN + "Saludos, Don Xavi. Disculpe la confusión, continúe.");
            return;
        }

        if (plugin.getHonorManager() != null) {
            plugin.getHonorManager().addHonor(culprit, -20, "Infracción vista por la policía: " + reason);
        }

        List<String> warns = plugin.getPlayerWarnings().get(culprit.getName().toLowerCase());
        int warnCount = (warns != null ? warns.size() : 0) + 1;

        plugin.addWarning(culprit.getName(), "Visto por el Oficial de Policía: " + reason, "Policía");

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
                            + ChatColor.YELLOW + "Motivo: " + ChatColor.WHITE + "Reincidencia de delitos graves presenciados por el Agente Torres (3 advertencias).\n"
                            + ChatColor.AQUA + "Puedes volver a entrar, pero a la 4ª advertencia serás baneado permanentemente.");
                }
            });
        } else if (warnCount >= 4) {
            plugin.banPlayer(culprit.getName(), "Reincidencia extrema de delitos graves presenciados por la policía (4 advertencias)");
        }
    }

    public Map<String, PatrolSector> getSectors() {
        return sectors;
    }

    public PatrolSector getActiveSector() {
        return activeSector;
    }
}
