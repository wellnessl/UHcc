package com.takimliuhc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

public final class TeamManager {

    public record Col(String code, NamedTextColor color) {}

    public static final class UTeam {
        public final int number;
        public final String name;
        public final Col col;
        public UUID leader;
        public final Map<UUID, String> members = new LinkedHashMap<>();
        org.bukkit.scoreboard.Team sb;

        UTeam(int number, String name, Col col) {
            this.number = number;
            this.name = name;
            this.col = col;
        }

        public String display() {
            return col.code() + name;
        }

        public List<Player> online() {
            List<Player> out = new ArrayList<>();
            for (UUID id : members.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) out.add(p);
            }
            return out;
        }
    }

    private static final Col[] PALETTE = {
            new Col("&c", NamedTextColor.RED),
            new Col("&9", NamedTextColor.BLUE),
            new Col("&a", NamedTextColor.GREEN),
            new Col("&e", NamedTextColor.YELLOW),
            new Col("&d", NamedTextColor.LIGHT_PURPLE),
            new Col("&b", NamedTextColor.AQUA),
            new Col("&6", NamedTextColor.GOLD),
            new Col("&5", NamedTextColor.DARK_PURPLE),
            new Col("&3", NamedTextColor.DARK_AQUA),
            new Col("&2", NamedTextColor.DARK_GREEN),
            new Col("&4", NamedTextColor.DARK_RED),
            new Col("&7", NamedTextColor.GRAY)
    };

    private final TeamUHC plugin;
    private final Map<Integer, UTeam> teams = new LinkedHashMap<>();
    private final Map<UUID, UTeam> byPlayer = new HashMap<>();
    private final Map<UUID, UTeam> invites = new HashMap<>();
    private int counter = 0;
    private boolean locked = false;

    public TeamManager(TeamUHC plugin) {
        this.plugin = plugin;
        // Önceki oturumdan scoreboard.dat'ta kalmış takımları temizle
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (org.bukkit.scoreboard.Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith("uhc_")) t.unregister();
        }
    }

    public Collection<UTeam> all() { return new ArrayList<>(teams.values()); }
    public UTeam of(UUID id) { return byPlayer.get(id); }
    public UTeam of(Player p) { return byPlayer.get(p.getUniqueId()); }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean v) { locked = v; }
    public int maxSize() { return Math.max(1, plugin.getConfig().getInt("teams.size", 2)); }

    public boolean sameTeam(UUID a, UUID b) {
        UTeam ta = byPlayer.get(a);
        return ta != null && ta == byPlayer.get(b);
    }

    public UTeam create(Player leader) {
        counter++;
        Col col = PALETTE[(counter - 1) % PALETTE.length];
        String name = plugin.getConfig().getString("teams.name-format", "Takım %number%")
                .replace("%number%", String.valueOf(counter));
        UTeam t = new UTeam(counter, name, col);
        t.leader = leader.getUniqueId();

        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String sbName = "uhc_" + counter;
        org.bukkit.scoreboard.Team old = sb.getTeam(sbName);
        if (old != null) old.unregister();
        org.bukkit.scoreboard.Team st = sb.registerNewTeam(sbName);
        st.color(col.color());
        st.setAllowFriendlyFire(plugin.getConfig().getBoolean("teams.friendly-fire", false));
        st.setCanSeeFriendlyInvisibles(true);
        t.sb = st;

        teams.put(counter, t);
        addMember(t, leader);
        return t;
    }

    public void addMember(UTeam t, Player p) {
        t.members.put(p.getUniqueId(), p.getName());
        byPlayer.put(p.getUniqueId(), t);
        t.sb.addEntry(p.getName());
    }

    private void removeMember(UTeam t, UUID id) {
        String name = t.members.remove(id);
        byPlayer.remove(id);
        if (name != null) t.sb.removeEntry(name);
        if (t.members.isEmpty()) {
            dissolve(t);
        } else if (id.equals(t.leader)) {
            t.leader = t.members.keySet().iterator().next();
        }
    }

    private void dissolve(UTeam t) {
        teams.remove(t.number);
        try {
            t.sb.unregister();
        } catch (IllegalStateException ignored) {
        }
    }

    /** Oyuncuyu takımından çıkarır. Takımı yoksa false döner. */
    public boolean leave(Player p) {
        UTeam t = of(p);
        if (t == null) return false;
        removeMember(t, p.getUniqueId());
        if (teams.containsKey(t.number)) {
            for (Player m : t.online()) plugin.send(m, "team-left", "%player%", p.getName());
        }
        return true;
    }

    public void removeOffline() {
        for (UTeam t : all()) {
            for (UUID id : new ArrayList<>(t.members.keySet())) {
                if (Bukkit.getPlayer(id) == null) removeMember(t, id);
            }
        }
    }

    public void invite(Player from, Player to) {
        if (locked) { plugin.send(from, "team-locked"); return; }
        if (of(to) != null) { plugin.send(from, "team-target-in-team", "%player%", to.getName()); return; }
        UTeam t = of(from);
        if (t == null) {
            t = create(from);
            plugin.send(from, "team-created", "%team%", t.display());
        }
        if (t.members.size() >= maxSize()) {
            plugin.send(from, "team-full", "%size%", String.valueOf(maxSize()));
            return;
        }
        final UTeam team = t;
        invites.put(to.getUniqueId(), team);
        plugin.send(from, "team-invite-sent", "%player%", to.getName());
        plugin.send(to, "team-invite-received", "%player%", from.getName(), "%team%", team.display());
        int timeout = plugin.getConfig().getInt("teams.invite-timeout", 30);
        Bukkit.getScheduler().runTaskLater(plugin, () -> invites.remove(to.getUniqueId(), team), timeout * 20L);
    }

    public void accept(Player p) {
        if (locked) { plugin.send(p, "team-locked"); return; }
        UTeam t = invites.remove(p.getUniqueId());
        if (t == null || !teams.containsKey(t.number)) { plugin.send(p, "team-no-invite"); return; }
        if (of(p) != null) { plugin.send(p, "team-already"); return; }
        if (t.members.size() >= maxSize()) {
            plugin.send(p, "team-full", "%size%", String.valueOf(maxSize()));
            return;
        }
        addMember(t, p);
        for (Player m : t.online()) plugin.send(m, "team-joined", "%player%", p.getName());
    }

    public void clearInvites(Player p) {
        invites.remove(p.getUniqueId());
    }

    public void teamChat(UTeam t, Player from, String text) {
        String fmt = plugin.getConfig().getString("teams.chat.format", "&b[Takım] &f%player%&7: &b%message%");
        Component line = Msg.c(fmt.replace("%player%", from.getName()))
                .replaceText(TextReplacementConfig.builder()
                        .matchLiteral("%message%")
                        .replacement(Component.text(text))
                        .build());
        for (Player m : t.online()) m.sendMessage(line);
    }

    public void sendList(Player to) {
        if (teams.isEmpty()) { plugin.send(to, "team-list-empty"); return; }
        plugin.send(to, "team-list-header");
        for (UTeam t : teams.values()) {
            to.sendMessage(Msg.c(plugin.msg("team-list-line",
                    "%team%", t.display(),
                    "%members%", String.join(", ", t.members.values()))));
        }
    }

    public void clearAll() {
        for (UTeam t : all()) dissolve(t);
        byPlayer.clear();
        invites.clear();
        counter = 0;
    }
}
