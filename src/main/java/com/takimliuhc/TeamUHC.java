package com.takimliuhc;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TeamUHC extends JavaPlugin {

    private TeamManager teams;
    private Game game;
    private NamespacedKey goldenHeadKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        goldenHeadKey = new NamespacedKey(this, "golden_head");
        teams = new TeamManager(this);
        game = new Game(this);

        Commands cmds = new Commands(this);
        for (String name : new String[]{"uhc", "team", "tc"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(cmds);
                pc.setTabCompleter(cmds);
            }
        }
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        registerGoldenHead();
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
        if (teams != null) teams.clearAll();
        Bukkit.removeRecipe(goldenHeadKey);
    }

    public void reload() {
        reloadConfig();
        registerGoldenHead();
    }

    public TeamManager teams() { return teams; }
    public Game game() { return game; }
    public NamespacedKey goldenHeadKey() { return goldenHeadKey; }

    // ---------------- Mesaj yardımcıları ----------------

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace(kv[i], kv[i + 1]);
        }
        return s;
    }

    public String msg(String key, String... kv) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null) raw = "&cEksik mesaj: " + key;
        return apply(raw, kv);
    }

    public List<String> lines(String key, String... kv) {
        List<String> out = new ArrayList<>();
        String path = "messages." + key;
        if (getConfig().isList(path)) {
            for (String l : getConfig().getStringList(path)) out.add(apply(l, kv));
        } else {
            out.add(msg(key, kv));
        }
        return out;
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(Msg.c(getConfig().getString("prefix", "") + msg(key, kv)));
    }

    public void broadcast(String key, String... kv) {
        Bukkit.broadcast(Msg.c(getConfig().getString("prefix", "") + msg(key, kv)));
    }

    public void broadcastLines(String key, String... kv) {
        for (String l : lines(key, kv)) Bukkit.broadcast(Msg.c(l));
    }

    // ---------------- Altın kafa ----------------

    public boolean isGoldenHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(goldenHeadKey, PersistentDataType.BYTE);
    }

    public ItemStack goldenHeadItem() {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        applyTexture(m, getConfig().getString("golden-head.texture", ""));
        m.displayName(Msg.item(getConfig().getString("golden-head.name", "&6Altın Kafa")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String l : getConfig().getStringList("golden-head.lore")) lore.add(Msg.item(l));
        if (!lore.isEmpty()) m.lore(lore);
        m.getPersistentDataContainer().set(goldenHeadKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    /** texture: minecraft-heads "Value" (base64) ya da textures.minecraft.net URL'si. */
    private void applyTexture(SkullMeta meta, String tex) {
        if (tex == null || tex.isBlank()) return;
        try {
            String raw = tex.trim();
            String urlStr;
            if (raw.startsWith("http")) {
                urlStr = raw;
            } else {
                String json = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
                Matcher mt = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (!mt.find()) throw new IllegalArgumentException("texture JSON içinde url yok");
                urlStr = mt.group(1);
            }
            UUID id = UUID.nameUUIDFromBytes(("goldenhead:" + urlStr).getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createPlayerProfile(id, "GoldenHead");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(urlStr).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception ex) {
            getLogger().warning("golden-head.texture okunamadı: " + ex.getMessage());
        }
    }

    private void registerGoldenHead() {
        Bukkit.removeRecipe(goldenHeadKey);
        if (!getConfig().getBoolean("golden-head.enabled", true)) return;
        ShapedRecipe r = new ShapedRecipe(goldenHeadKey, goldenHeadItem());
        r.shape("GGG", "GHG", "GGG");
        r.setIngredient('G', Material.GOLD_INGOT);
        r.setIngredient('H', Material.PLAYER_HEAD);
        Bukkit.addRecipe(r);
    }
}
