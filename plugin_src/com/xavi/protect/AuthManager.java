package com.xavi.protect;

import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.PacketPlayOutTitle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor de Autenticación, Registro y Login sin temporizador de expulsión.
 * Protege contra suplantaciones de identidad y robo de cuentas/inventarios.
 */
public class AuthManager {

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;

    // Jugadores actualmente conectados que aún no han hecho /register o /login
    private final Set<UUID> unauthenticated = Collections.synchronizedSet(new HashSet<UUID>());

    // Ubicaciones de congelamiento mientras se autentican
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<UUID, Location>();

    // Tarea de recordatorio periódico no intrusivo
    private BukkitTask reminderTask = null;

    public AuthManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        load();
        startReminderTask();

        // Si el plugin se recarga con jugadores dentro, requerir autenticación
        for (Player p : Bukkit.getOnlinePlayers()) {
            requireAuth(p);
        }
    }

    public synchronized void load() {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
            cfg = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            plugin.getLogger().severe("[AuthManager] Error al cargar auth.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (cfg != null && file != null) {
                cfg.save(file);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[AuthManager] Error al guardar auth.yml: " + e.getMessage());
        }
    }

    private void startReminderTask() {
        // Recordatorio cada 8 segundos (160 ticks) sin temporizador de expulsión
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (isUnauthenticated(p)) {
                        sendAuthPrompt(p, false);
                    }
                }
            }
        }, 160L, 160L);
    }

    public boolean isUnauthenticated(Player p) {
        if (p == null) return false;
        return unauthenticated.contains(p.getUniqueId());
    }

    public boolean isRegistered(String playerName) {
        if (playerName == null || cfg == null) return false;
        return cfg.contains("players." + playerName.toLowerCase() + ".hash");
    }

    public void requireAuth(Player p) {
        if (p == null) return;
        unauthenticated.add(p.getUniqueId());
        freezeLocations.put(p.getUniqueId(), p.getLocation());
        sendAuthPrompt(p, true);
    }

    public void handleQuit(Player p) {
        if (p == null) return;
        unauthenticated.remove(p.getUniqueId());
        freezeLocations.remove(p.getUniqueId());
    }

    public Location getFreezeLocation(Player p) {
        if (p == null) return null;
        return freezeLocations.get(p.getUniqueId());
    }

    public void sendAuthPrompt(Player p, boolean withSound) {
        if (p == null || !p.isOnline()) return;

        boolean registered = isRegistered(p.getName());
        if (!registered) {
            // Instrucciones para registrarse
            p.sendMessage(ChatColor.GOLD + "==================================================");
            p.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "       🔒 ¡BIENVENIDO AL SERVIDOR DE XAVI! 🔒");
            p.sendMessage(ChatColor.GRAY + " Para proteger tu cuenta e inventario de posibles robos:");
            p.sendMessage(ChatColor.WHITE + " Debes registrar una contraseña personal para tu nombre.");
            p.sendMessage("");
            p.sendMessage(ChatColor.YELLOW + " 👉 Escribe en el chat:");
            p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "    /register <contraseña> <repetir_contraseña>");
            p.sendMessage("");
            p.sendMessage(ChatColor.GRAY + " Ejemplo: " + ChatColor.AQUA + "/register 123456 123456");
            p.sendMessage(ChatColor.DARK_GRAY + " (Sin temporizador: tómate el tiempo que necesites)");
            p.sendMessage(ChatColor.GOLD + "==================================================");

            sendTitle(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "¡REGÍSTRATE!",
                    ChatColor.WHITE + "Usa: " + ChatColor.GREEN + "/register <clave> <clave>");

            if (withSound) {
                try {
                    p.playSound(p.getLocation(), Sound.NOTE_PLING, 1.2f, 1.5f);
                } catch (Exception ignored) {}
            }
        } else {
            // Instrucciones para iniciar sesión
            p.sendMessage(ChatColor.GOLD + "==================================================");
            p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "       🔐 ¡HOLA DE NUEVO, " + p.getName().toUpperCase() + "! 🔐");
            p.sendMessage(ChatColor.GRAY + " Esta cuenta está protegida con contraseña.");
            p.sendMessage(ChatColor.WHITE + " Inicia sesión para recuperar tu inventario y jugar.");
            p.sendMessage("");
            p.sendMessage(ChatColor.YELLOW + " 👉 Escribe en el chat:");
            p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "    /login <tu_contraseña>");
            p.sendMessage("");
            p.sendMessage(ChatColor.GRAY + " Ejemplo: " + ChatColor.GREEN + "/login 123456");
            p.sendMessage(ChatColor.DARK_GRAY + " (Sin temporizador: no se te expulsará por tardar)");
            p.sendMessage(ChatColor.GOLD + "==================================================");

            sendTitle(p, ChatColor.AQUA + "" + ChatColor.BOLD + "¡INICIA SESIÓN!",
                    ChatColor.WHITE + "Usa: " + ChatColor.GREEN + "/login <tu_contraseña>");

            if (withSound) {
                try {
                    p.playSound(p.getLocation(), Sound.NOTE_PLING, 1.0f, 1.2f);
                } catch (Exception ignored) {}
            }
        }
    }

    public boolean register(Player p, String pass1, String pass2) {
        if (p == null) return false;
        String name = p.getName().toLowerCase();

        if (isRegistered(name)) {
            p.sendMessage(ChatColor.RED + "❌ Esta cuenta ya está registrada. Inicia sesión con: "
                    + ChatColor.YELLOW + "/login <tu_contraseña>");
            playErrorSound(p);
            return false;
        }

        if (pass1 == null || pass2 == null) {
            p.sendMessage(ChatColor.RED + "❌ Uso correcto: " + ChatColor.YELLOW + "/register <contraseña> <repetir_contraseña>");
            p.sendMessage(ChatColor.GRAY + "Debes escribir la contraseña dos veces para asegurar que no te equivocaste.");
            playErrorSound(p);
            return false;
        }

        if (!pass1.equals(pass2)) {
            p.sendMessage(ChatColor.RED + "❌ Las contraseñas no coinciden.");
            p.sendMessage(ChatColor.YELLOW + "Asegúrate de escribir exactamente la misma contraseña en ambas palabras.");
            playErrorSound(p);
            return false;
        }

        if (pass1.length() < 4) {
            p.sendMessage(ChatColor.RED + "❌ La contraseña es demasiado corta. Mínimo 4 caracteres.");
            playErrorSound(p);
            return false;
        }

        String salt = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String hash = hashPassword(pass1, salt);
        String ip = (p.getAddress() != null && p.getAddress().getAddress() != null)
                ? p.getAddress().getAddress().getHostAddress() : "desconocida";
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        cfg.set("players." + name + ".hash", hash);
        cfg.set("players." + name + ".salt", salt);
        cfg.set("players." + name + ".last_ip", ip);
        cfg.set("players." + name + ".registered_at", date);
        save();

        unauthenticated.remove(p.getUniqueId());
        freezeLocations.remove(p.getUniqueId());

        p.sendMessage("");
        p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ ¡CUENTA REGISTRADA CON ÉXITO!");
        p.sendMessage(ChatColor.YELLOW + "Tu contraseña ha sido guardada de forma segura.");
        p.sendMessage(ChatColor.GRAY + "La próxima vez que entres, solo tendrás que escribir: "
                + ChatColor.WHITE + "/login " + pass1);
        p.sendMessage(ChatColor.GREEN + "¡Ya puedes moverte y jugar con total libertad!");
        p.sendMessage("");

        sendTitle(p, ChatColor.GREEN + "" + ChatColor.BOLD + "¡BIENVENIDO!",
                ChatColor.YELLOW + "Cuenta protegida y lista");

        try {
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 1.0f, 1.2f);
        } catch (Exception ignored) {}

        plugin.getLogger().info("[Auth] Jugador registrado con éxito: " + p.getName() + " (IP: " + ip + ")");
        plugin.onPlayerAuthenticated(p);
        return true;
    }

    public boolean login(Player p, String pass) {
        if (p == null) return false;
        String name = p.getName().toLowerCase();

        if (!isRegistered(name)) {
            p.sendMessage(ChatColor.RED + "❌ Esta cuenta aún no está registrada.");
            p.sendMessage(ChatColor.YELLOW + "Usa: /register <contraseña> <repetir_contraseña> para crear tu clave.");
            playErrorSound(p);
            return false;
        }

        if (!isUnauthenticated(p)) {
            p.sendMessage(ChatColor.YELLOW + "Ya has iniciado sesión correctamente.");
            return true;
        }

        if (pass == null || pass.isEmpty()) {
            p.sendMessage(ChatColor.RED + "❌ Uso correcto: " + ChatColor.YELLOW + "/login <tu_contraseña>");
            playErrorSound(p);
            return false;
        }

        String salt = cfg.getString("players." + name + ".salt", "");
        String storedHash = cfg.getString("players." + name + ".hash", "");
        String computedHash = hashPassword(pass, salt);

        if (!computedHash.equals(storedHash)) {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "❌ CONTRASEÑA INCORRECTA.");
            p.sendMessage(ChatColor.YELLOW + "Por favor, verifica mayúsculas y minúsculas e inténtalo de nuevo.");
            p.sendMessage(ChatColor.GRAY + "Usa: " + ChatColor.WHITE + "/login <tu_contraseña>");
            playErrorSound(p);
            return false;
        }

        String ip = (p.getAddress() != null && p.getAddress().getAddress() != null)
                ? p.getAddress().getAddress().getHostAddress() : "desconocida";
        cfg.set("players." + name + ".last_ip", ip);
        cfg.set("players." + name + ".last_login", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        save();

        unauthenticated.remove(p.getUniqueId());
        freezeLocations.remove(p.getUniqueId());

        p.sendMessage("");
        p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✔ ¡SESIÓN INICIADA CORRECTAMENTE!");
        p.sendMessage(ChatColor.YELLOW + "¡Hola de nuevo, " + ChatColor.GOLD + p.getName() + ChatColor.YELLOW + "! Buen juego.");
        p.sendMessage("");

        sendTitle(p, ChatColor.GREEN + "" + ChatColor.BOLD + "¡SESIÓN INICIADA!",
                ChatColor.WHITE + "¡Bienvenido de vuelta!");

        try {
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 1.0f, 1.2f);
        } catch (Exception ignored) {}

        plugin.getLogger().info("[Auth] Sesión iniciada con éxito: " + p.getName() + " (IP: " + ip + ")");
        plugin.onPlayerAuthenticated(p);
        return true;
    }

    public boolean changePassword(Player p, String oldPass, String newPass) {
        if (p == null) return false;
        String name = p.getName().toLowerCase();

        if (!isRegistered(name)) {
            p.sendMessage(ChatColor.RED + "Tu cuenta aún no está registrada.");
            return false;
        }

        if (oldPass == null || newPass == null || newPass.length() < 4) {
            p.sendMessage(ChatColor.RED + "Uso: /changepassword <contraseña_actual> <nueva_contraseña>");
            p.sendMessage(ChatColor.GRAY + "La nueva contraseña debe tener al menos 4 caracteres.");
            return false;
        }

        String salt = cfg.getString("players." + name + ".salt", "");
        String storedHash = cfg.getString("players." + name + ".hash", "");
        if (!hashPassword(oldPass, salt).equals(storedHash)) {
            p.sendMessage(ChatColor.RED + "❌ La contraseña actual no es correcta.");
            playErrorSound(p);
            return false;
        }

        String newSalt = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String newHash = hashPassword(newPass, newSalt);

        cfg.set("players." + name + ".salt", newSalt);
        cfg.set("players." + name + ".hash", newHash);
        save();

        p.sendMessage(ChatColor.GREEN + "✔ ¡Contraseña actualizada con éxito!");
        try {
            p.playSound(p.getLocation(), Sound.ORB_PICKUP, 1.0f, 1.2f);
        } catch (Exception ignored) {}
        return true;
    }

    public boolean unregister(String targetName) {
        if (targetName == null) return false;
        String name = targetName.toLowerCase();
        if (!isRegistered(name)) return false;

        cfg.set("players." + name, null);
        save();

        Player p = Bukkit.getPlayerExact(targetName);
        if (p != null && p.isOnline()) {
            requireAuth(p);
        }
        return true;
    }

    public int getRegisteredCount() {
        if (cfg == null || !cfg.contains("players")) return 0;
        return cfg.getConfigurationSection("players").getKeys(false).size();
    }

    public Set<String> getRegisteredPlayers() {
        if (cfg == null || !cfg.contains("players")) return Collections.emptySet();
        return cfg.getConfigurationSection("players").getKeys(false);
    }

    public void sendTitle(Player p, String title, String subtitle) {
        if (p == null || !p.isOnline()) return;
        try {
            CraftPlayer cp = (CraftPlayer) p;
            PacketPlayOutTitle length = new PacketPlayOutTitle(5, 50, 15);
            IChatBaseComponent cTitle = IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + title + "\"}");
            PacketPlayOutTitle pTitle = new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.TITLE, cTitle);
            IChatBaseComponent cSub = IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + subtitle + "\"}");
            PacketPlayOutTitle pSub = new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.SUBTITLE, cSub);

            cp.getHandle().playerConnection.sendPacket(length);
            cp.getHandle().playerConnection.sendPacket(pTitle);
            cp.getHandle().playerConnection.sendPacket(pSub);
        } catch (Exception ignored) {}
    }

    private void playErrorSound(Player p) {
        try {
            p.playSound(p.getLocation(), Sound.VILLAGER_NO, 1.0f, 1.0f);
        } catch (Exception ignored) {}
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((password + ":" + salt).getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
