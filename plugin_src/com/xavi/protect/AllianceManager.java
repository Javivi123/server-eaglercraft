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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AllianceManager {

    public static class Alliance {
        public String name;
        public String leader;
        public Set<String> members = new HashSet<String>();
        public boolean friendlyFire = false;
        public boolean openJoin = true; // Por defecto abierta para que cualquiera pueda unirse facilmente
        public Set<String> enemies = new HashSet<String>();
        public int kills = 0;
        public int warsWon = 0;

        public Alliance(String name, String leader) {
            this.name = name;
            this.leader = leader.toLowerCase();
            this.members.add(leader.toLowerCase());
            this.openJoin = true;
        }
    }

    private final XaviAdminProtect plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, Alliance> alliances = new LinkedHashMap<String, Alliance>(); // allianceName.toLowerCase -> Alliance
    private final Map<String, String> playerToAlliance = new HashMap<String, String>(); // player.toLowerCase -> allianceName.toLowerCase
    private final Map<String, String> pendingInvites = new HashMap<String, String>(); // targetPlayer.toLowerCase -> allianceName.toLowerCase

    public AllianceManager(XaviAdminProtect plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "alliances.yml");
        load();
    }

    public synchronized void load() {
        alliances.clear();
        playerToAlliance.clear();
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (!file.exists()) {
                file.createNewFile();
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = cfg.getConfigurationSection("alliances");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String name = sec.getString(key + ".name", key);
                    String leader = sec.getString(key + ".leader", "");
                    Alliance al = new Alliance(name, leader);
                    al.friendlyFire = sec.getBoolean(key + ".friendlyFire", false);
                    al.openJoin = sec.getBoolean(key + ".openJoin", true);
                    al.kills = sec.getInt(key + ".kills", 0);
                    al.warsWon = sec.getInt(key + ".warsWon", 0);

                    List<String> memList = sec.getStringList(key + ".members");
                    if (memList != null) {
                        for (String m : memList) {
                            al.members.add(m.toLowerCase());
                            playerToAlliance.put(m.toLowerCase(), key.toLowerCase());
                        }
                    } else if (!leader.isEmpty()) {
                        al.members.add(leader.toLowerCase());
                        playerToAlliance.put(leader.toLowerCase(), key.toLowerCase());
                    }

                    List<String> enemyList = sec.getStringList(key + ".enemies");
                    if (enemyList != null) {
                        for (String e : enemyList) {
                            al.enemies.add(e.toLowerCase());
                        }
                    }

                    alliances.put(key.toLowerCase(), al);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[AllianceManager] Error cargando alliances.yml: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            if (cfg == null) cfg = new YamlConfiguration();
            cfg.set("alliances", null);

            for (Map.Entry<String, Alliance> entry : alliances.entrySet()) {
                String key = entry.getKey();
                Alliance al = entry.getValue();
                cfg.set("alliances." + key + ".name", al.name);
                cfg.set("alliances." + key + ".leader", al.leader);
                cfg.set("alliances." + key + ".friendlyFire", al.friendlyFire);
                cfg.set("alliances." + key + ".openJoin", al.openJoin);
                cfg.set("alliances." + key + ".kills", al.kills);
                cfg.set("alliances." + key + ".warsWon", al.warsWon);
                cfg.set("alliances." + key + ".members", new ArrayList<String>(al.members));
                cfg.set("alliances." + key + ".enemies", new ArrayList<String>(al.enemies));
            }
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[AllianceManager] Error guardando alliances.yml: " + e.getMessage());
        }
    }

    public Alliance getAlliance(String playerName) {
        if (playerName == null) return null;
        String alKey = playerToAlliance.get(playerName.toLowerCase());
        if (alKey == null) return null;
        return alliances.get(alKey);
    }

    public Alliance getAllianceByName(String name) {
        if (name == null) return null;
        return alliances.get(name.toLowerCase().trim());
    }

    public List<Alliance> getAllAlliances() {
        return new ArrayList<Alliance>(alliances.values());
    }

    public boolean areAllies(String p1, String p2) {
        if (p1 == null || p2 == null) return false;
        String a1 = playerToAlliance.get(p1.toLowerCase());
        String a2 = playerToAlliance.get(p2.toLowerCase());
        return a1 != null && a1.equalsIgnoreCase(a2);
    }

    public boolean areAtWar(String p1, String p2) {
        Alliance al1 = getAlliance(p1);
        Alliance al2 = getAlliance(p2);
        if (al1 == null || al2 == null) return false;
        if (al1 == al2) return false;
        return al1.enemies.contains(al2.name.toLowerCase()) || al2.enemies.contains(al1.name.toLowerCase());
    }

    public boolean createAlliance(Player player, String name) {
        String pName = player.getName();
        if (name == null || name.trim().isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Uso: /crearclan <nombre> (ejemplo: /crearclan Lobos)");
            return false;
        }
        String cleanName = name.trim();
        if (cleanName.length() < 2 || cleanName.length() > 16) {
            player.sendMessage(ChatColor.RED + "El nombre de la alianza debe tener entre 2 y 16 caracteres.");
            return false;
        }
        if (!cleanName.matches("^[a-zA-Z0-9_]+$")) {
            player.sendMessage(ChatColor.RED + "El nombre solo puede contener letras, numeros y guion bajo.");
            return false;
        }

        Alliance existingWithName = getAllianceByName(cleanName);
        if (existingWithName != null) {
            player.sendMessage(ChatColor.RED + "Ya existe una alianza con el nombre '" + cleanName + "'. Elige otro.");
            return false;
        }

        Alliance current = getAlliance(pName);
        if (current != null) {
            if (current.members.size() <= 1) {
                // Solo estaba el, eliminar la antigua
                alliances.remove(current.name.toLowerCase());
            } else {
                player.sendMessage(ChatColor.RED + "Ya perteneces a la alianza [" + current.name + "]. Sal primero con /salirclan");
                return false;
            }
        }

        Alliance al = new Alliance(cleanName, pName);
        al.openJoin = true; // Abierta para facilitar que los amigos se unan
        alliances.put(cleanName.toLowerCase(), al);
        playerToAlliance.put(pName.toLowerCase(), cleanName.toLowerCase());
        save();

        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "========================================");
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "🛡️ ¡NUEVA ALIANZA FUNDADA!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + pName + ChatColor.GREEN + " ha creado la alianza "
                + ChatColor.AQUA + "" + ChatColor.BOLD + "[" + cleanName + "]" + ChatColor.GREEN + "!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "👉 Para unirte escribe: " + ChatColor.YELLOW + "/unirse " + cleanName);
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "========================================");

        try {
            player.playSound(player.getLocation(), Sound.LEVEL_UP, 1.0f, 1.2f);
        } catch (Exception ignored) {}

        return true;
    }

    public boolean invite(Player sender, String targetName) {
        Alliance al = getAlliance(sender.getName());
        if (al == null) {
            sender.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza. ¡Crea una con /crearclan <nombre>!");
            return false;
        }
        if (targetName == null || targetName.trim().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Uso: /invitar <jugador>");
            return false;
        }
        Player target = Bukkit.getPlayerExact(targetName.trim());
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "El jugador '" + targetName + "' no está conectado ahora mismo.");
            return false;
        }
        if (target.getName().equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(ChatColor.RED + "No puedes invitarte a ti mismo.");
            return false;
        }

        pendingInvites.put(target.getName().toLowerCase(), al.name.toLowerCase());
        sender.sendMessage(ChatColor.GREEN + "¡Has invitado a " + target.getName() + " a tu alianza [" + al.name + "]!");

        // Send clickable tellraw invitation to target!
        String tellrawCmd = "tellraw " + target.getName() + " [\"\",{\"text\":\"========================================\\n\",\"color\":\"gold\",\"bold\":true},"
                + "{\"text\":\"👑 ¡" + sender.getName() + " te ha invitado a unirte a \",\"color\":\"yellow\",\"bold\":true},"
                + "{\"text\":\"[" + al.name + "]\",\"color\":\"aqua\",\"bold\":true},"
                + "{\"text\":\"!\\n\",\"color\":\"yellow\",\"bold\":true},"
                + "{\"text\":\"👉 \",\"color\":\"green\"},"
                + "{\"text\":\"[¡HAZ CLICK AQUÍ PARA ENTRAR!]\",\"color\":\"green\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/unirse " + al.name + "\"}},"
                + "{\"text\":\"\\n o escribe en el chat: \",\"color\":\"gray\"},"
                + "{\"text\":\"/unirse\",\"color\":\"yellow\",\"bold\":true},"
                + "{\"text\":\"\\n========================================\",\"color\":\"gold\",\"bold\":true}]";

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tellrawCmd);
            target.playSound(target.getLocation(), Sound.NOTE_PLING, 1.2f, 1.8f);
        } catch (Exception e) {
            target.sendMessage(ChatColor.GOLD + "========================================");
            target.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + sender.getName() + " te ha invitado a unirte a [" + al.name + "]!");
            target.sendMessage(ChatColor.GREEN + "👉 Escribe /unirse o /unirse " + al.name + " para entrar.");
            target.sendMessage(ChatColor.GOLD + "========================================");
        }

        return true;
    }

    public boolean joinAlliance(Player player, String requestedAllianceName) {
        String pName = player.getName().toLowerCase();
        Alliance targetAlliance = null;

        if (requestedAllianceName != null && !requestedAllianceName.trim().isEmpty()) {
            targetAlliance = getAllianceByName(requestedAllianceName.trim());
            if (targetAlliance == null) {
                player.sendMessage(ChatColor.RED + "No existe ninguna alianza llamada '" + requestedAllianceName + "'.");
                player.sendMessage(ChatColor.GRAY + "Escribe /alianza lista para ver los nombres disponibles.");
                return false;
            }
        } else {
            // No name provided: check pending invite
            String pending = pendingInvites.get(pName);
            if (pending != null) {
                targetAlliance = alliances.get(pending.toLowerCase());
            }
            if (targetAlliance == null) {
                // If there's only 1 alliance on the whole server, join that one!
                if (alliances.size() == 1) {
                    targetAlliance = alliances.values().iterator().next();
                } else if (alliances.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "Aún no hay ninguna alianza creada. ¡Sé el primero con /crearclan <nombre>!");
                    return false;
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Escribe /unirse <nombre_de_alianza> (Ejemplo: /unirse " + alliances.values().iterator().next().name + ")");
                    player.sendMessage(ChatColor.GRAY + "Escribe /alianza lista para ver todas las alianzas.");
                    return false;
                }
            }
        }

        // Check if player is already in this exact alliance
        Alliance current = getAlliance(player.getName());
        if (current != null && current == targetAlliance) {
            player.sendMessage(ChatColor.YELLOW + "¡Ya formas parte de la alianza [" + targetAlliance.name + "]!");
            return true;
        }

        // Check privacy
        boolean isInvited = false;
        String pending = pendingInvites.get(pName);
        if (pending != null && pending.equalsIgnoreCase(targetAlliance.name)) {
            isInvited = true;
        }

        if (!targetAlliance.openJoin && !isInvited) {
            player.sendMessage(ChatColor.RED + "La alianza [" + targetAlliance.name + "] es privada. Pídele al líder que te invite con /invitar " + player.getName());
            return false;
        }

        // If player was in an old clan, leave it automatically
        if (current != null) {
            current.members.remove(pName);
            playerToAlliance.remove(pName);
            if (current.members.isEmpty()) {
                alliances.remove(current.name.toLowerCase());
            } else if (current.leader.equalsIgnoreCase(pName)) {
                current.leader = current.members.iterator().next();
            }
            player.sendMessage(ChatColor.GRAY + "Has dejado tu alianza anterior para unirte a [" + targetAlliance.name + "].");
        }

        // Add to new alliance
        targetAlliance.members.add(pName);
        playerToAlliance.put(pName, targetAlliance.name.toLowerCase());
        pendingInvites.remove(pName);
        save();

        broadcastToAlliance(targetAlliance, ChatColor.GREEN + "¡" + player.getName() + " se ha unido a la alianza " + targetAlliance.name + "!");
        player.sendMessage(ChatColor.GOLD + "========================================");
        player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "¡TE HAS UNIDO A LA ALIANZA [" + targetAlliance.name + "]!");
        player.sendMessage(ChatColor.YELLOW + "Líder: " + targetAlliance.leader + " | Miembros (" + targetAlliance.members.size() + "): " + String.join(", ", targetAlliance.members));
        player.sendMessage(ChatColor.AQUA + "Usa /ac <mensaje> para hablar en privado con tu clan.");
        player.sendMessage(ChatColor.GOLD + "========================================");

        try {
            player.playSound(player.getLocation(), Sound.LEVEL_UP, 1.0f, 1.5f);
        } catch (Exception ignored) {}

        return true;
    }

    public boolean acceptInvite(Player player, String requestedAllianceName) {
        return joinAlliance(player, requestedAllianceName);
    }

    public boolean leave(Player player) {
        Alliance al = getAlliance(player.getName());
        if (al == null) {
            player.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        String pName = player.getName().toLowerCase();
        al.members.remove(pName);
        playerToAlliance.remove(pName);

        if (al.members.isEmpty()) {
            alliances.remove(al.name.toLowerCase());
            save();
            Bukkit.broadcastMessage(ChatColor.GOLD + "[ALIANZA] La alianza [" + al.name + "] se ha disuelto al quedar sin miembros.");
        } else {
            if (al.leader.equalsIgnoreCase(pName)) {
                String newLeader = al.members.iterator().next();
                al.leader = newLeader;
                broadcastToAlliance(al, ChatColor.YELLOW + player.getName() + " ha dejado la alianza. El nuevo líder es " + newLeader + ".");
            } else {
                broadcastToAlliance(al, ChatColor.YELLOW + player.getName() + " ha abandonado la alianza.");
            }
            save();
        }
        player.sendMessage(ChatColor.YELLOW + "Has salido de la alianza [" + al.name + "].");
        return true;
    }

    public boolean kick(Player leader, String targetName) {
        Alliance al = getAlliance(leader.getName());
        if (al == null) {
            leader.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        if (!al.leader.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "Solo el líder (" + al.leader + ") puede expulsar miembros.");
            return false;
        }
        if (targetName.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "No puedes expulsarte a ti mismo. Usa /salirclan.");
            return false;
        }
        String targetLower = targetName.toLowerCase();
        if (!al.members.contains(targetLower)) {
            leader.sendMessage(ChatColor.RED + targetName + " no es miembro de tu alianza.");
            return false;
        }

        al.members.remove(targetLower);
        playerToAlliance.remove(targetLower);
        save();

        broadcastToAlliance(al, ChatColor.RED + targetName + " ha sido expulsado de la alianza por el líder.");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            target.sendMessage(ChatColor.RED + "Has sido expulsado de la alianza [" + al.name + "].");
        }
        return true;
    }

    public boolean toggleFriendlyFire(Player leader) {
        Alliance al = getAlliance(leader.getName());
        if (al == null) {
            leader.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        if (!al.leader.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "Solo el líder puede cambiar el fuego amigo.");
            return false;
        }
        al.friendlyFire = !al.friendlyFire;
        save();
        String status = al.friendlyFire ? (ChatColor.RED + "ACTIVADO (pueden pegarse entre aliados)") : (ChatColor.GREEN + "DESACTIVADO (protegidos contra golpes)");
        broadcastToAlliance(al, ChatColor.GOLD + "[ALIANZA] Fuego amigo: " + status);
        return true;
    }

    public boolean toggleOpenJoin(Player leader) {
        Alliance al = getAlliance(leader.getName());
        if (al == null) {
            leader.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        if (!al.leader.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "Solo el líder puede cambiar la privacidad de la alianza.");
            return false;
        }
        al.openJoin = !al.openJoin;
        save();
        String status = al.openJoin
                ? (ChatColor.GREEN + "ABIERTA (cualquiera puede unirse con /unirse " + al.name + ")")
                : (ChatColor.RED + "PRIVADA (solo mediante /invitar)");
        broadcastToAlliance(al, ChatColor.GOLD + "[ALIANZA] Entrada a la alianza: " + status);
        return true;
    }

    public boolean declareWar(Player leader, String targetAllianceName) {
        Alliance al = getAlliance(leader.getName());
        if (al == null) {
            leader.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        if (!al.leader.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "Solo el líder puede declarar la guerra.");
            return false;
        }
        Alliance target = getAllianceByName(targetAllianceName);
        if (target == null) {
            leader.sendMessage(ChatColor.RED + "La alianza " + targetAllianceName + " no existe.");
            return false;
        }
        if (target == al) {
            leader.sendMessage(ChatColor.RED + "No puedes declarar la guerra a tu propia alianza.");
            return false;
        }

        al.enemies.add(target.name.toLowerCase());
        save();

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "========================================");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "⚔️ ¡¡DECLARACIÓN DE GUERRA!!");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "La alianza " + ChatColor.RED + "[" + al.name + "]"
                + ChatColor.YELLOW + " ha declarado la guerra a " + ChatColor.RED + "[" + target.name + "]" + ChatColor.YELLOW + "!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "¡Los miembros de ambas alianzas ahora son enemigos en batalla!");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "========================================");
        return true;
    }

    public boolean makePeace(Player leader, String targetAllianceName) {
        Alliance al = getAlliance(leader.getName());
        if (al == null) {
            leader.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza.");
            return false;
        }
        if (!al.leader.equalsIgnoreCase(leader.getName())) {
            leader.sendMessage(ChatColor.RED + "Solo el líder puede firmar la paz.");
            return false;
        }
        Alliance target = getAllianceByName(targetAllianceName);
        if (target == null) {
            leader.sendMessage(ChatColor.RED + "La alianza " + targetAllianceName + " no existe.");
            return false;
        }

        al.enemies.remove(target.name.toLowerCase());
        target.enemies.remove(al.name.toLowerCase());
        save();

        Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "🕊️ [PAZ] Las alianzas "
                + ChatColor.AQUA + "[" + al.name + "]" + ChatColor.GREEN + " y "
                + ChatColor.AQUA + "[" + target.name + "]" + ChatColor.GREEN + " han firmado la paz.");
        return true;
    }

    public void chat(Player sender, String message) {
        Alliance al = getAlliance(sender.getName());
        if (al == null) {
            sender.sendMessage(ChatColor.RED + "No perteneces a ninguna alianza. ¡Únete a una con /unirse o crea la tuya con /crearclan <nombre>!");
            return;
        }
        String formatted = ChatColor.AQUA + "[Chat " + al.name + "] "
                + ChatColor.YELLOW + sender.getName() + ChatColor.GRAY + ": "
                + ChatColor.WHITE + message;

        for (String memberName : al.members) {
            Player p = Bukkit.getPlayerExact(memberName);
            if (p != null && p.isOnline()) {
                p.sendMessage(formatted);
            }
        }
    }

    public void broadcastToAlliance(Alliance al, String message) {
        for (String memberName : al.members) {
            Player p = Bukkit.getPlayerExact(memberName);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }
}
