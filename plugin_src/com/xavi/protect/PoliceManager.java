package com.xavi.protect;

import net.minecraft.server.v1_8_R3.EntityCreature;
import net.minecraft.server.v1_8_R3.Navigation;
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
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PoliceManager {

    private final XaviAdminProtect plugin;
    private LivingEntity policeEntity = null;
    private boolean killedThisSession = false;
    private int currentWaypointIndex = 0;
    private int patrolTaskId = -1;
    private final Random random = new Random();

    // Circuito de patrullaje por la zona construida y caminos
    private final double[][] waypoints = {
        {-272.0, 77.0, -6.0},   // Plaza y fachada principal casa de Xavi
        {-285.0, 77.0, -8.0},   // Calle Oeste
        {-275.0, 76.0, 5.0},    // Avenida Sur
        {-262.0, 76.0, 0.0},    // Cruce Este hacia expansión
        {-252.0, 75.0, -12.0},  // Paseo Noreste
        {-280.0, 77.0, -22.0}   // Camino Norte
    };

    public PoliceManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
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

    public synchronized void spawnPolice() {
        if (killedThisSession) {
            plugin.getLogger().info("[PoliceManager] El policía fue abatido en esta sesión. No reaparece hasta el próximo reinicio.");
            return;
        }

        // Si ya existe y está vivo, reutilizarlo
        if (policeEntity != null && policeEntity.isValid() && !policeEntity.isDead()) {
            return;
        }

        World w = Bukkit.getWorld("world");
        if (w == null) {
            if (!Bukkit.getWorlds().isEmpty()) {
                w = Bukkit.getWorlds().get(0);
            } else {
                return;
            }
        }

        // Limpiar duplicados previos si los hubiera
        for (Entity e : w.getEntities()) {
            if (e instanceof LivingEntity && !(e instanceof Player)) {
                String cName = ((LivingEntity) e).getCustomName();
                if (cName != null && cName.contains("POLICÍA")) {
                    e.remove();
                }
            }
        }

        Location spawnLoc = new Location(w, -272.5, 77.0, -6.5, 0f, 0f);
        Zombie cop = (Zombie) w.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        cop.setBaby(false);
        cop.setVillager(false);
        cop.setCanPickupItems(false);
        cop.setRemoveWhenFarAway(false);

        // Nombre oficial claro: es un BOT POLICÍA, no un jugador humano
        cop.setCustomName(ChatColor.BLUE + "" + ChatColor.BOLD + "🚔 [POLICÍA] Agente Torres");
        cop.setCustomNameVisible(true);

        cop.setMaxHealth(60.0);
        cop.setHealth(60.0);

        // Equipamiento oficial de policía
        EntityEquipment eq = cop.getEquipment();

        ItemStack helmet = new ItemStack(Material.CHAINMAIL_HELMET); // Gorra reglamentaria (evita quemarse con el sol)
        ItemMeta hMeta = helmet.getItemMeta();
        hMeta.setDisplayName(ChatColor.BLUE + "Gorra Oficial de Policía");
        helmet.setItemMeta(hMeta);
        eq.setHelmet(helmet);
        eq.setHelmetDropChance(0.0f);

        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta cMeta = (LeatherArmorMeta) chest.getItemMeta();
        cMeta.setColor(Color.fromRGB(16, 44, 87)); // Azul marino policía
        cMeta.setDisplayName(ChatColor.BLUE + "Chaleco Oficial de Policía");
        chest.setItemMeta(cMeta);
        eq.setChestplate(chest);
        eq.setChestplateDropChance(0.0f);

        ItemStack legs = new ItemStack(Material.LEATHER_LEGGINGS);
        LeatherArmorMeta lMeta = (LeatherArmorMeta) legs.getItemMeta();
        lMeta.setColor(Color.fromRGB(20, 25, 45));
        lMeta.setDisplayName(ChatColor.BLUE + "Pantalón Oficial de Policía");
        legs.setItemMeta(lMeta);
        eq.setLeggings(legs);
        eq.setLeggingsDropChance(0.0f);

        ItemStack boots = new ItemStack(Material.IRON_BOOTS);
        ItemMeta bMeta = boots.getItemMeta();
        bMeta.setDisplayName(ChatColor.BLUE + "Botas Policiales Tácticas");
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
        plugin.getLogger().info("[PoliceManager] Oficial de Policía Agente Torres desplegado en servicio en X=-272, Y=77, Z=-6.");
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
                // Solo vigilar si hay al menos 1 jugador conectado al servidor
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    return;
                }

                if (killedThisSession) {
                    return;
                }

                if (policeEntity == null || !policeEntity.isValid() || policeEntity.isDead()) {
                    spawnPolice();
                    return;
                }

                // Evitar que el zombie ataque a inocentes como un monstruo normal
                if (policeEntity instanceof Creature) {
                    Creature cr = (Creature) policeEntity;
                    if (cr.getTarget() instanceof Player) {
                        Player targetPlayer = (Player) cr.getTarget();
                        // Si el objetivo no es un forajido o agresor activo, borrar objetivo
                        if (targetPlayer.isOnline() && !targetPlayer.isDead()) {
                            // Dejar que vigile o persiga solo si hay causa
                        } else {
                            cr.setTarget(null);
                        }
                    }
                }

                patrolNextStep();
            }
        }, 100L, 160L); // Cada 8 segundos ejecuta una acción de patrullaje
    }

    private void patrolNextStep() {
        if (policeEntity == null || !policeEntity.isValid()) return;

        // Comprobar si hay jugadores cerca en la zona para patrullar a su alrededor
        Player nearest = null;
        double minD = 32.0 * 32.0;
        Location pLoc = policeEntity.getLocation();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(pLoc.getWorld())) {
                double d = p.getLocation().distanceSquared(pLoc);
                if (d < minD) {
                    minD = d;
                    nearest = p;
                }
            }
        }

        double destX, destY, destZ;

        // 35% de probabilidad de acercarse a vigilar al jugador cercano si está en el pueblo
        if (nearest != null && random.nextDouble() < 0.35) {
            Location nLoc = nearest.getLocation();
            // Mantenerse a unos 4 bloques de distancia respetando su espacio
            Vector offset = pLoc.toVector().subtract(nLoc.toVector()).normalize().multiply(4.0);
            destX = nLoc.getX() + offset.getX();
            destY = nLoc.getY();
            destZ = nLoc.getZ() + offset.getZ();

            // Saludo o aviso de rutina ocasional (10% de probabilidad)
            if (random.nextDouble() < 0.10) {
                nearest.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.GRAY + "Circulen con tranquilidad, la zona está bajo vigilancia oficial.");
                nearest.playSound(nearest.getLocation(), Sound.NOTE_PLING, 0.7f, 2.0f);
            }
        } else {
            // Avanzar al siguiente punto del circuito de patrullaje
            double[] wp = waypoints[currentWaypointIndex];
            destX = wp[0];
            destY = wp[1];
            destZ = wp[2];
            currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.length;
        }

        // Navegación nativa de Minecraft (camina por bloques, esquiva esquinas, sube escalones)
        try {
            if (policeEntity instanceof CraftCreature) {
                EntityCreature nmsCreature = ((CraftCreature) policeEntity).getHandle();
                nmsCreature.getNavigation().a(destX, destY, destZ, 1.10);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Comprobación visual estricta de Campo de Visión (FOV) y Línea de Visión (Raycast)
     * Solo ve si el objetivo está frente a él (no a su espalda) y sin muros opacos de por medio.
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
        if (distSq > 28.0 * 28.0) {
            return false; // Rango visual máximo: 28 bloques
        }

        // 1. Ángulo horizontal (Cono de visión frontal)
        Vector policeDir = eye.getDirection().setY(0).normalize();
        Vector toTarget = targetLoc.clone().subtract(eye).toVector().setY(0).normalize();
        double dot = policeDir.dot(toTarget);

        // Si dot < 0.25 (más de ~75 grados hacia atrás o a su espalda), NO LO VE
        if (dot < 0.25) {
            return false; // Está a su espalda o fuera de su visión periférica
        }

        // 2. Línea de visión directa (Bloques opacos que obstruyen)
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
                    return false; // Hay una pared, techo o muro bloqueando su vista física
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
     * El jugador ataca al policía.
     * Si le atacó por la espalda sin que el policía le vea, el policía NO hace nada ("no se entera").
     * Si le atacó de frente en su campo de visión, el policía actúa contra la agresión.
     */
    public void handleAttackOnPolice(Player attacker) {
        if (policeEntity == null || !policeEntity.isValid()) return;

        boolean sawAttacker = canPoliceSee(attacker.getEyeLocation());

        if (!sawAttacker) {
            // ATAQUE POR LA ESPALDA / SIGILO
            // "pero si alguien le ataca y no se entera no haga nada, la unica forma de librarse de él es matarlo sin que le vean"
            attacker.sendMessage(ChatColor.DARK_GRAY + "🗡 [Sigilo] Has golpeado al policía por la espalda sin que te vea. No se ha enterado de quién fue.");
            return; // No hace nada, solo recibe el daño normalmente
        }

        // VISTO EN FLAGRANTE: Agresión directa a la autoridad en su cara
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.ANVIL_LAND, 0.8f, 1.5f);

        // El policía se encara al agresor
        Vector toAttacker = attacker.getLocation().toVector().subtract(policeEntity.getLocation().toVector());
        Location faceLoc = policeEntity.getLocation().setDirection(toAttacker);
        policeEntity.teleport(faceLoc);

        if (policeEntity instanceof Creature) {
            ((Creature) policeEntity).setTarget(attacker);
        }

        // Taser / Golpe reglamentario: Ralentización y aturdimiento
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 120, 2));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 1));

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.RED + "¡ALTO! ¡Agresión directa a un agente de la ley cometida por " + ChatColor.YELLOW + attacker.getName() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        // Registrar advertencia o castigo según reincidencia
        applyPoliceSanction(attacker, "Agresión directa a un agente de policía en servicio");
    }

    /**
     * Cuando el policía muere en el servidor.
     */
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

    /**
     * Cuando el policía presencia un acto delictivo en su campo de visión.
     */
    public void witnessCrime(Player culprit, String crimeTitle, String description, Location crimeLocation) {
        if (policeEntity == null || !policeEntity.isValid() || policeEntity.isDead() || killedThisSession) {
            return;
        }

        // Comprobar si el policía tiene línea de visión física y está encarado al delito
        if (!canPoliceSee(crimeLocation)) {
            return; // Fuera de vista o tras un muro: el policía no lo vio
        }

        // Alerta policial
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.NOTE_PLING, 1.5f, 2.0f);
        policeEntity.getWorld().playSound(policeEntity.getLocation(), Sound.IRONGOLEM_HIT, 1.0f, 1.0f);

        // Mirar hacia la escena del crimen
        Vector dir = crimeLocation.toVector().subtract(policeEntity.getLocation().toVector());
        policeEntity.teleport(policeEntity.getLocation().setDirection(dir));

        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");
        Bukkit.broadcastMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.RED + "¡ALTO EN NOMBRE DE LA LEY, " + ChatColor.YELLOW + culprit.getName().toUpperCase() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "  Infracción presenciada: " + ChatColor.WHITE + crimeTitle + " (" + description + ")");
        Bukkit.broadcastMessage(ChatColor.DARK_BLUE + "══════════════════════════════════════════════════");

        applyPoliceSanction(culprit, crimeTitle);
    }

    /**
     * Aplica la sanción correspondiente según reincidencia (/warn, kick, o ban)
     */
    public void applyPoliceSanction(final Player culprit, String reason) {
        if (culprit == null || !culprit.isOnline()) return;

        // Si es el administrador supremo (Xavi), el policía lo reconoce y no lo sanciona
        if (culprit.getName().equalsIgnoreCase(plugin.getOwnerName())) {
            culprit.sendMessage(ChatColor.BLUE + "🚔 [POLICÍA] Agente Torres: " + ChatColor.GREEN + "Saludos, Don Xavi. Disculpe la confusión, continúe.");
            return;
        }

        // Restar honor
        if (plugin.getHonorManager() != null) {
            plugin.getHonorManager().addHonor(culprit, -20, "Infracción vista por la policía: " + reason);
        }

        // Añadir advertencia al sistema
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
}
