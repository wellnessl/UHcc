package com.takimliuhc;

import com.takimliuhc.TeamManager.UTeam;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Commands implements CommandExecutor, TabCompleter {

    private final TeamUHC plugin;

    public Commands(TeamUHC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "uhc" -> uhc(s, a);
            case "team" -> team(s, a);
            case "tc" -> tc(s, a);
            default -> { }
        }
        return true;
    }

    private void uhc(CommandSender s, String[] a) {
        if (a.length == 0) { plugin.send(s, "uhc-usage"); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> plugin.game().start(s);
            case "stop" -> plugin.game().stop(s);
            case "reload" -> {
                plugin.reload();
                plugin.send(s, "reloaded");
            }
            case "revive" -> {
                if (a.length < 2) { plugin.send(s, "uhc-usage"); return; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { plugin.send(s, "player-not-found"); return; }
                plugin.game().revive(s, t);
            }
            default -> plugin.send(s, "uhc-usage");
        }
    }

    private void team(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.send(s, "only-players"); return; }
        TeamManager tm = plugin.teams();
        if (a.length == 0) { plugin.send(p, "team-usage"); return; }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (tm.isLocked()) { plugin.send(p, "team-locked"); return; }
                if (tm.of(p) != null) { plugin.send(p, "team-already"); return; }
                UTeam t = tm.create(p);
                plugin.send(p, "team-created", "%team%", t.display());
            }
            case "invite" -> {
                if (a.length < 2) { plugin.send(p, "team-usage"); return; }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { plugin.send(p, "player-not-found"); return; }
                if (target.equals(p)) { plugin.send(p, "team-self-invite"); return; }
                tm.invite(p, target);
            }
            case "accept" -> tm.accept(p);
            case "leave" -> {
                if (tm.isLocked()) { plugin.send(p, "team-locked"); return; }
                if (tm.leave(p)) plugin.send(p, "team-left-self");
                else plugin.send(p, "team-none");
            }
            case "list" -> tm.sendList(p);
            default -> plugin.send(p, "team-usage");
        }
    }

    private void tc(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.send(s, "only-players"); return; }
        UTeam t = plugin.teams().of(p);
        if (t == null) { plugin.send(p, "team-none"); return; }
        if (a.length == 0) { plugin.send(p, "tc-usage"); return; }
        plugin.teams().teamChat(t, p, String.join(" ", a));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("uhc")) {
            if (a.length == 1) out.addAll(List.of("start", "stop", "reload", "revive"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("revive")) addPlayers(out);
        } else if (name.equals("team")) {
            if (a.length == 1) out.addAll(List.of("create", "invite", "accept", "leave", "list"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("invite")) addPlayers(out);
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private void addPlayers(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }
}
