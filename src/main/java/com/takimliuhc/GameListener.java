package com.takimliuhc;

import com.takimliuhc.TeamManager.UTeam;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

public final class GameListener implements Listener {

    private final TeamUHC plugin;

    public GameListener(TeamUHC plugin) {
        this.plugin = plugin;
    }

    private Game game() { return plugin.game(); }
    private TeamManager tm() { return plugin.teams(); }
    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ---------------- Hasar / PvP / takım dostu ateşi ----------------

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player damager = null;
        if (e.getDamager() instanceof Player p) {
            damager = p;
        } else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            damager = p;
        }
        if (damager == null || damager.equals(victim)) return;
        if (!game().isRunning()) return;

        if (!game().isPvpOn()) {
            e.setCancelled(true);
            return;
        }
        if (!cfg().getBoolean("teams.friendly-fire", false)
                && tm().sameTeam(damager.getUniqueId(), victim.getUniqueId())) {
            e.setCancelled(true);
            plugin.send(damager, "friendly-fire-blocked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobbyDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && game().isWaiting()
                && cfg().getBoolean("game.lobby-protect", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobbyHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && game().isWaiting()
                && cfg().getBoolean("game.lobby-protect", true)) {
            e.setCancelled(true);
        }
    }

    // ---------------- Doğal yenilenme kapatma ----------------

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (!game().isRegenOff()) return;
        EntityRegainHealthEvent.RegainReason r = e.getRegainReason();
        if (r == EntityRegainHealthEvent.RegainReason.SATIATED
                || r == EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);
        }
    }

    // ---------------- Ölüm ----------------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        if (!game().isRunning() || !game().isAlive(v.getUniqueId())) return;

        if (cfg().getBoolean("heads.drop-on-death", true)) {
            e.getDrops().add(makeHead(v));
        }

        Player k = v.getKiller();
        UTeam vt = tm().of(v);
        UTeam kt = k == null ? null : tm().of(k);
        String vTeam = vt == null ? "-" : vt.display();
        String text;
        if (k != null) {
            text = plugin.msg("death-killer",
                    "%victim%", v.getName(), "%victim_team%", vTeam,
                    "%killer%", k.getName(), "%killer_team%", kt == null ? "-" : kt.display());
        } else {
            text = plugin.msg("death", "%victim%", v.getName(), "%victim_team%", vTeam);
        }
        e.deathMessage(Msg.c(text));

        final Location where = v.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> game().handleDeath(v, where));
    }

    private ItemStack makeHead(Player v) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) head.getItemMeta();
        m.setOwningPlayer(v);
        m.displayName(Msg.item(cfg().getString("heads.name", "&6%player% Kafası")
                .replace("%player%", v.getName())));
        head.setItemMeta(m);
        return head;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!game().isRunning() || !game().isParticipant(id) || game().isAlive(id)) return;
        Location loc = game().deathLocation(id);
        if (loc != null) e.setRespawnLocation(loc);
        Bukkit.getScheduler().runTask(plugin, () -> p.setGameMode(GameMode.SPECTATOR));
    }

    // ---------------- Giriş / çıkış ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (game().isRunning()) {
            if (!game().isParticipant(id)) {
                p.setGameMode(GameMode.SPECTATOR);
                p.teleport(game().spectatorSpawn());
                plugin.send(p, "joined-as-spectator");
            } else if (!game().isAlive(id)) {
                p.setGameMode(GameMode.SPECTATOR);
            }
            return;
        }
        if (game().isWaiting()) {
            UTeam t = tm().of(p);
            for (String line : plugin.lines("welcome", "%team%", t == null ? "-" : t.display())) {
                p.sendMessage(Msg.c(line));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        tm().clearInvites(p);
        if (game().isWaiting()) tm().leave(p);
    }

    // ---------------- Başarım mesajlarını gizle ----------------

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (cfg().getBoolean("game.hide-advancements", true)) {
            e.message(null);
        }
    }

    // ---------------- Altın kafa ----------------

    /** Altın kafaya sağ tık: yer, efektleri ver, 1 adet tüket. */
    @EventHandler
    public void onUseHead(PlayerInteractEvent e) {
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItem(hand);
        if (!plugin.isGoldenHead(item)) return;

        e.setCancelled(true); // kafa blok olarak yerleşmesin

        FileConfiguration c = cfg();
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                c.getInt("golden-head.regen-seconds", 9) * 20,
                c.getInt("golden-head.regen-amplifier", 1)));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                c.getInt("golden-head.absorption-seconds", 120) * 20,
                c.getInt("golden-head.absorption-amplifier", 0)));
        p.playSound(p.getLocation(), "entity.generic.eat", 1f, 1f);

        if (p.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
            p.getInventory().setItem(hand, item.getAmount() <= 0 ? null : item);
        }
    }

    /** Altın kafa, altın kafayı yapmak için malzeme olarak kullanılamasın. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe sr) || !sr.getKey().equals(plugin.goldenHeadKey())) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (plugin.isGoldenHead(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---------------- Sohbet ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UTeam t = tm().of(p);
        String prefix = cfg().getString("teams.chat.prefix", "!");
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());

        if (t != null && !prefix.isEmpty() && plain.startsWith(prefix) && plain.length() > prefix.length()) {
            e.setCancelled(true);
            tm().teamChat(t, p, plain.substring(prefix.length()).trim());
            return;
        }

        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> {
            UTeam st = tm().of(source);
            Component tag = st == null
                    ? Component.empty()
                    : Component.text("[" + st.name + "] ", st.col.color());
            return tag.append(Component.text(source.getName(), NamedTextColor.WHITE))
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(message);
        }));
    }
}
