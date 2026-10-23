package com.takimliuhc;

import com.takimliuhc.TeamManager.UTeam;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public final class Game {

    public enum State { WAITING, RUNNING, ENDING }

    private final TeamUHC plugin;
    private final Random rnd = new Random();

    private State state = State.WAITING;
    private World world;
    private BukkitTask task;
    private BukkitTask endTask;
    private int elapsed;
    private boolean pvpOn, regenOff, glowOn;

    private final Set<UUID> alive = new HashSet<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, Location> deathLocs = new HashMap<>();
    private final Airdrop airdrop;

    public Game(TeamUHC plugin) {
        this.plugin = plugin;
        this.airdrop = new Airdrop(plugin);
    }

    // ---------------- Durum sorguları ----------------

    public boolean isRunning() { return state == State.RUNNING; }
    public boolean isWaiting() { return state == State.WAITING; }
    public boolean isPvpOn() { return pvpOn; }
    public boolean isRegenOff() { return regenOff; }
    public boolean isAlive(UUID id) { return alive.contains(id); }
    public boolean isParticipant(UUID id) { return participants.contains(id); }
    public Location deathLocation(UUID id) { return deathLocs.get(id); }

    public Location spectatorSpawn() {
        World w = world != null ? world : Bukkit.getWorlds().get(0);
        return w.getSpawnLocation();
    }

    // ---------------- Başlatma ----------------

    public void start(CommandSender sender) {
        if (state != State.WAITING) {
            plugin.send(sender, "already-running");
            return;
        }
        FileConfiguration c = plugin.getConfig();
        TeamManager tm = plugin.teams();

        World w = Bukkit.getWorld(c.getString("game.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        world = w;

        tm.removeOffline();

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("uhc.spectate")) {
                p.setGameMode(GameMode.SPECTATOR);
            } else {
                players.add(p);
            }
        }
        assignLeftovers(players);

        int min = c.getInt("game.min-teams", 2);
        if (tm.all().size() < min) {
            plugin.send(sender, "not-enough-teams", "%min%", String.valueOf(min));
            return;
        }

        tm.setLocked(true);
        alive.clear();
        participants.clear();
        deathLocs.clear();
        elapsed = 0;
        pvpOn = false;
        regenOff = false;
        glowOn = false;

        WorldBorder wb = world.getWorldBorder();
        wb.setCenter(c.getDouble("border.center.x", 0), c.getDouble("border.center.z", 0));
        wb.setSize(c.getDouble("border.start-size", 2000));
        wb.setDamageAmount(c.getDouble("border.damage", 1.0));
        wb.setDamageBuffer(0);
        wb.setWarningDistance(c.getInt("border.warning-distance", 10));

        world.setTime(0);
        world.setStorm(false);
        if (c.getBoolean("game.set-hard-difficulty", true)) world.setDifficulty(Difficulty.HARD);

        scatter(players);
        for (Player p : players) {
            resetPlayer(p);
            alive.add(p.getUniqueId());
            participants.add(p.getUniqueId());
        }

        airdrop.loadLoot();
        state = State.RUNNING;
        plugin.broadcastLines("game-start",
                "%team-size%", String.valueOf(tm.maxSize()),
                "%pvp-time%", String.valueOf(c.getInt("game.pvp-time", 1500) / 60),
                "%border-time%", String.valueOf(c.getInt("game.border-start-time", 1500) / 60));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void assignLeftovers(List<Player> players) {
        TeamManager tm = plugin.teams();
        int size = tm.maxSize();
        List<Player> left = players.stream().filter(p -> tm.of(p) == null).collect(Collectors.toList());

        if (!plugin.getConfig().getBoolean("teams.auto-assign", true)) {
            for (Player p : left) tm.create(p);
            return;
        }
        Collections.shuffle(left);
        Iterator<Player> it = left.iterator();
        // önce eksik takımları doldur
        for (UTeam t : tm.all()) {
            while (t.members.size() < size && it.hasNext()) tm.addMember(t, it.next());
        }
        // kalanlardan yeni takımlar kur
        UTeam cur = null;
        while (it.hasNext()) {
            Player p = it.next();
            if (cur == null || cur.members.size() >= size) cur = tm.create(p);
            else tm.addMember(cur, p);
        }
    }

    private void scatter(List<Player> players) {
        FileConfiguration c = plugin.getConfig();
        WorldBorder wb = world.getWorldBorder();
        Location center = wb.getCenter();
        double half = wb.getSize() / 2.0 * 0.9;
        double radius = c.getDouble("teams.spawn-radius", 5);
        boolean together = c.getBoolean("teams.spawn-together", true);

        for (UTeam t : plugin.teams().all()) {
            Location base = safeSpot(center.getX(), center.getZ(), half);
            for (Player p : t.online()) {
                if (!players.contains(p)) continue;
                Location dest = together
                        ? safeSpot(base.getX(), base.getZ(), radius)
                        : safeSpot(center.getX(), center.getZ(), half);
                p.teleport(dest);
            }
        }
    }

    private Location safeSpot(double cx, double cz, double range) {
        Location loc = null;
        for (int i = 0; i < 20; i++) {
            double x = cx + (rnd.nextDouble() * 2 - 1) * range;
            double z = cz + (rnd.nextDouble() * 2 - 1) * range;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int y = world.getHighestBlockYAt(bx, bz);
            loc = new Location(world, bx + 0.5, y + 1, bz + 0.5);
            if (!world.getBlockAt(bx, y, bz).isLiquid()) break;
        }
        return loc;
    }

    private void resetPlayer(Player p) {
        FileConfiguration c = plugin.getConfig();
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExp(0f);
        p.setLevel(0);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setGlowing(false);
        int prot = c.getInt("game.start-protection-seconds", 20);
        if (prot > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, prot * 20, 4));
        }
    }

    // ---------------- Zamanlayıcı ----------------

    private void tick() {
        FileConfiguration c = plugin.getConfig();
        elapsed++;

        int pvp = c.getInt("game.pvp-time", 1500);
        int regen = c.getInt("game.regen-disable-time", 1500);
        int bStart = c.getInt("game.border-start-time", 1500);
        int shrink = c.getInt("game.border-shrink-time", 2400);
        int bEnd = bStart + shrink;
        int glow = bEnd + c.getInt("game.glow-delay", 480);

        if (elapsed == pvp) {
            pvpOn = true;
            plugin.broadcastLines("pvp-enabled");
        }
        if (elapsed == regen) {
            regenOff = true;
            plugin.broadcastLines("regen-disabled");
        }
        if (elapsed == bStart) {
            world.getWorldBorder().setSize(c.getDouble("border.end-size", 20), shrink);
            plugin.broadcastLines("border-start",
                    "%size%", String.valueOf((int) c.getDouble("border.end-size", 20)),
                    "%time%", String.valueOf(shrink / 60));
        }
        if (elapsed == bEnd) {
            plugin.broadcastLines("border-finished",
                    "%time%", String.valueOf(c.getInt("game.glow-delay", 480) / 60));
        }
        if (elapsed == glow && c.getBoolean("glow.enabled", true)) {
            glowOn = true;
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.setGlowing(true);
            }
            plugin.broadcastLines("glow-enabled");
        }

        if (c.getBoolean("airdrops.enabled", true)) {
            int first = c.getInt("airdrops.first-drop-seconds", 900);
            int interval = Math.max(30, c.getInt("airdrops.interval-seconds", 300));
            if (elapsed >= first && (elapsed - first) % interval == 0) {
                airdrop.drop(world);
            }
        }

        if (c.getBoolean("timers.enabled", true)) {
            announce("pvp", pvp);
            announce("regen", regen);
            announce("border", bStart);
            announce("border-end", bEnd);
            announce("glow", glow);
        }
    }

    private void announce(String key, int target) {
        int remaining = target - elapsed;
        if (remaining <= 0) return;
        int interval = Math.max(1, plugin.getConfig().getInt("timers.interval", 60));
        boolean show = remaining % interval == 0 || remaining == 30 || remaining == 10 || remaining <= 5;
        if (show) plugin.broadcast("timer-" + key, "%time%", fmt(remaining));
    }

    private String fmt(int s) {
        String min = plugin.msg("unit-minute");
        String sec = plugin.msg("unit-second");
        if (s >= 60) {
            int m = s / 60;
            int r = s % 60;
            return m + " " + min + (r > 0 ? " " + r + " " + sec : "");
        }
        return s + " " + sec;
    }

    // ---------------- Ölüm / kazanma ----------------

    public void handleDeath(Player victim, Location where) {
        if (state != State.RUNNING) return;
        UUID id = victim.getUniqueId();
        if (!alive.remove(id)) return;
        deathLocs.put(id, where);

        TeamManager tm = plugin.teams();
        UTeam t = tm.of(victim);
        if (t != null) {
            long left = t.members.keySet().stream().filter(alive::contains).count();
            for (Player m : t.online()) {
                if (alive.contains(m.getUniqueId())) {
                    plugin.send(m, "teammate-died", "%player%", victim.getName(), "%alive%", String.valueOf(left));
                }
            }
            if (left == 0) {
                plugin.broadcastLines("team-eliminated",
                        "%team%", t.display(),
                        "%teams-left%", String.valueOf(aliveTeams().size()));
            }
        }
        checkWin();
    }

    private Set<UTeam> aliveTeams() {
        Set<UTeam> set = new HashSet<>();
        for (UUID id : alive) {
            UTeam t = plugin.teams().of(id);
            if (t != null) set.add(t);
        }
        return set;
    }

    private void checkWin() {
        Set<UTeam> left = aliveTeams();
        boolean multi = plugin.teams().all().size() > 1;
        if (left.isEmpty()) {
            end(null);
        } else if (left.size() == 1 && multi) {
            end(left.iterator().next());
        }
    }

    private void end(UTeam winner) {
        state = State.ENDING;
        if (task != null) { task.cancel(); task = null; }
        if (winner != null) {
            plugin.broadcastLines("win",
                    "%team%", winner.display(),
                    "%winners%", String.join(", ", winner.members.values()));
        }
        int delay = plugin.getConfig().getInt("game.end-delay", 15);
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::resetAll, delay * 20L);
    }

    // ---------------- Durdurma / sıfırlama ----------------

    public void stop(CommandSender sender) {
        if (state == State.WAITING) {
            plugin.send(sender, "not-running");
            return;
        }
        resetAll();
        plugin.send(sender, "stopped");
    }

    public void shutdown() {
        if (state != State.WAITING) resetAll();
    }

    public void resetAll() {
        if (task != null) { task.cancel(); task = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }

        airdrop.clear();
        if (world != null) world.getWorldBorder().reset();
        Location spawn = spectatorSpawn();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (participants.contains(p.getUniqueId()) || p.getGameMode() == GameMode.SPECTATOR) {
                p.setGlowing(false);
                p.setGameMode(GameMode.SURVIVAL);
                for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
                p.getInventory().clear();
                p.setHealth(20.0);
                p.setFoodLevel(20);
                p.teleport(spawn);
            }
        }
        alive.clear();
        participants.clear();
        deathLocs.clear();
        pvpOn = false;
        regenOff = false;
        glowOn = false;
        state = State.WAITING;

        TeamManager tm = plugin.teams();
        tm.setLocked(false);
        if (plugin.getConfig().getBoolean("game.reset-teams-on-end", true)) tm.clearAll();
    }

    // ---------------- Canlandırma ----------------

    public void revive(CommandSender sender, Player target) {
        if (state != State.RUNNING) {
            plugin.send(sender, "not-running");
            return;
        }
        UUID id = target.getUniqueId();
        if (alive.contains(id)) {
            plugin.send(sender, "revive-already-alive");
            return;
        }
        participants.add(id);
        alive.add(id);
        resetPlayer(target);

        UTeam t = plugin.teams().of(target);
        Location dest = null;
        if (t != null) {
            for (Player m : t.online()) {
                if (!m.equals(target) && alive.contains(m.getUniqueId())) {
                    dest = m.getLocation();
                    break;
                }
            }
        }
        if (dest == null) dest = deathLocs.getOrDefault(id, spectatorSpawn());
        target.teleport(dest);
        if (glowOn) target.setGlowing(true);

        plugin.broadcast("revived", "%player%", target.getName(), "%team%", t == null ? "-" : t.display());
    }
}
