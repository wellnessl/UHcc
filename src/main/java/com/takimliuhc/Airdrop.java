package com.takimliuhc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Border içine rastgele sandık (airdrop) bırakır ve config'teki loot listesinden doldurur. */
public final class Airdrop {

    private record LootEntry(Material mat, boolean goldenHead, int min, int max, int weight) {}

    private final TeamUHC plugin;
    private final Random rnd = new Random();
    private final List<LootEntry> loot = new ArrayList<>();
    private final List<Location> chests = new ArrayList<>();

    public Airdrop(TeamUHC plugin) {
        this.plugin = plugin;
    }

    /** Loot formatı: MATERYAL:min-max:ağırlık  (örn. DIAMOND:1-3:10). Özel: GOLDEN_HEAD */
    public void loadLoot() {
        loot.clear();
        for (String line : plugin.getConfig().getStringList("airdrops.loot")) {
            try {
                String[] parts = line.trim().split(":");
                String name = parts[0].trim().toUpperCase(Locale.ROOT);
                String[] range = parts.length > 1 ? parts[1].trim().split("-") : new String[]{"1"};
                int min = Integer.parseInt(range[0].trim());
                int max = range.length > 1 ? Integer.parseInt(range[1].trim()) : min;
                int weight = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 10;
                if (max < min) max = min;
                if (weight < 1) weight = 1;

                if (name.equals("GOLDEN_HEAD")) {
                    loot.add(new LootEntry(null, true, min, max, weight));
                } else {
                    Material m = Material.matchMaterial(name);
                    if (m == null || m.isAir() || !m.isItem()) {
                        plugin.getLogger().warning("Airdrop loot: geçersiz materyal -> " + line);
                        continue;
                    }
                    loot.add(new LootEntry(m, false, min, max, weight));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Airdrop loot satırı okunamadı -> " + line);
            }
        }
        if (loot.isEmpty()) {
            plugin.getLogger().warning("Airdrop loot listesi boş! config.yml -> airdrops.loot kontrol et.");
        }
    }

    public void drop(World world) {
        FileConfiguration c = plugin.getConfig();
        if (loot.isEmpty()) return;

        WorldBorder wb = world.getWorldBorder();
        double half = Math.max(5, wb.getSize() / 2.0 - c.getDouble("airdrops.border-margin", 30));
        Location center = wb.getCenter();

        Location spot = null;
        for (int i = 0; i < 40; i++) {
            double x = center.getX() + (rnd.nextDouble() * 2 - 1) * half;
            double z = center.getZ() + (rnd.nextDouble() * 2 - 1) * half;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int y = world.getHighestBlockYAt(bx, bz);
            Block ground = world.getBlockAt(bx, y, bz);
            Block above = world.getBlockAt(bx, y + 1, bz);
            if (ground.isLiquid() || !above.getType().isAir()) continue;
            spot = above.getLocation();
            break;
        }
        if (spot == null) return;

        Block block = spot.getBlock();
        block.setType(Material.CHEST);
        BlockState state = block.getState();
        if (!(state instanceof Chest chest)) return;

        fill(chest.getBlockInventory());
        chests.add(spot);
        world.strikeLightningEffect(spot);

        plugin.broadcastLines("airdrop-dropped",
                "%x%", String.valueOf(spot.getBlockX()),
                "%y%", String.valueOf(spot.getBlockY()),
                "%z%", String.valueOf(spot.getBlockZ()));
    }

    private void fill(Inventory inv) {
        FileConfiguration c = plugin.getConfig();
        int min = Math.max(1, c.getInt("airdrops.items-per-chest.min", 6));
        int max = Math.max(min, c.getInt("airdrops.items-per-chest.max", 10));
        int count = Math.min(inv.getSize(), min + rnd.nextInt(max - min + 1));

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, rnd);

        Set<LootEntry> used = new HashSet<>();
        for (int i = 0; i < count; i++) {
            LootEntry e = pick(used);
            if (e == null) break;
            used.add(e);

            int amount = e.min() + rnd.nextInt(e.max() - e.min() + 1);
            ItemStack item;
            if (e.goldenHead()) {
                item = plugin.goldenHeadItem();
                item.setAmount(Math.max(1, Math.min(amount, 64)));
            } else {
                item = new ItemStack(e.mat(), Math.max(1, Math.min(amount, e.mat().getMaxStackSize())));
            }
            inv.setItem(slots.get(i), item);
        }
    }

    private LootEntry pick(Set<LootEntry> used) {
        int total = 0;
        for (LootEntry e : loot) if (!used.contains(e)) total += e.weight();
        if (total <= 0) return null;
        int r = rnd.nextInt(total);
        for (LootEntry e : loot) {
            if (used.contains(e)) continue;
            r -= e.weight();
            if (r < 0) return e;
        }
        return null;
    }

    /** Oyun bitince bırakılan sandıkları kaldırır. */
    public void clear() {
        for (Location l : chests) {
            Block b = l.getBlock();
            if (b.getState() instanceof Chest ch) ch.getBlockInventory().clear();
            if (b.getType() == Material.CHEST) b.setType(Material.AIR);
        }
        chests.clear();
    }
}
