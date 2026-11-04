package com.elytrapvp.antidupe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Eşya doğrulama motoru:
 *  - Stacklenmeyen eşyalara (alet, zırh, shulker...) benzersiz kimlik damgası vurur.
 *  - Aynı kimlik aynı anda iki yerde bulunursa kopyayı silmek yerine KARANTİNAYA alır.
 *  - Aşırı stack (overstack) ve iç içe shulker gibi imkansız durumları yakalar.
 */
public class ItemGuard {

    private final AntiDupe plugin;
    private final NamespacedKey key;
    /** eşya kimliği -> en son doğrulanmış sahibi */
    private final Map<UUID, UUID> holders = new HashMap<>();
    private final Set<Material> ignored = new HashSet<>();

    public ItemGuard(AntiDupe plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "item_id");
        reload();
    }

    public void reload() {
        ignored.clear();
        for (String s : plugin.getConfig().getStringList("settings.ignored-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) ignored.add(m);
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    public static boolean isShulker(Material m) {
        return m.name().endsWith("SHULKER_BOX");
    }

    private static boolean isAir(ItemStack i) {
        return i == null || i.getType() == Material.AIR;
    }

    private boolean isTracked(ItemStack i) {
        return i.getMaxStackSize() == 1 && !ignored.contains(i.getType());
    }

    private UUID parse(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ShulkerBox boxOf(ItemStack item, ItemMeta meta) {
        if (!isShulker(item.getType()) || !(meta instanceof BlockStateMeta)) return null;
        BlockStateMeta b = (BlockStateMeta) meta;
        if (!b.hasBlockState()) return null;
        return b.getBlockState() instanceof ShulkerBox ? (ShulkerBox) b.getBlockState() : null;
    }

    // ---------------------------------------------------------------- önleyici kontroller

    /** Vanilla'da imkansız eşya mı? (overstack, 0 adet, iç içe shulker). */
    public boolean isIllegal(ItemStack it) {
        if (isAir(it)) return false;
        if (it.getAmount() < 1 || it.getAmount() > Math.max(1, it.getMaxStackSize())) return true;
        if (isShulker(it.getType())) {
            ItemMeta m = it.getItemMeta();
            ShulkerBox box = m == null ? null : boxOf(it, m);
            if (box != null) {
                for (ItemStack in : box.getInventory().getContents()) {
                    if (isAir(in)) continue;
                    if (isShulker(in.getType())) return true;
                    if (in.getAmount() > Math.max(1, in.getMaxStackSize())) return true;
                }
            }
        }
        return false;
    }

    /** Bu eşya (veya içindekiler) oyuncuda ya da doğrulanmış başka bir oyuncuda zaten var mı? */
    public boolean isDuplicateFor(Player p, ItemStack it) {
        Set<UUID> ids = idsOf(it);
        if (ids.isEmpty()) return false;
        Set<UUID> mine = collect(p);
        boolean cross = plugin.getConfig().getBoolean("settings.cross-player-detection", true);
        for (UUID id : ids) {
            if (mine.contains(id)) return true;
            if (!cross) continue;
            UUID owner = holders.get(id);
            if (owner != null && !owner.equals(p.getUniqueId())) {
                Player op = Bukkit.getPlayer(owner);
                if (op != null && op.getGameMode() != GameMode.CREATIVE && collect(op).contains(id)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- okuma (değiştirmez)

    public Set<UUID> idsOf(ItemStack it) {
        Set<UUID> out = new HashSet<>();
        collectItem(it, out, 0);
        return out;
    }

    public Set<UUID> collect(Player p) {
        Set<UUID> out = new HashSet<>();
        for (ItemStack i : p.getInventory().getContents()) collectItem(i, out, 0);
        for (ItemStack i : p.getEnderChest().getContents()) collectItem(i, out, 0);
        collectItem(p.getItemOnCursor(), out, 0);
        return out;
    }

    private void collectItem(ItemStack it, Set<UUID> out, int depth) {
        if (isAir(it) || depth > 3) return;
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        UUID id = parse(m.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        if (id != null) out.add(id);
        ShulkerBox box = boxOf(it, m);
        if (box != null) {
            for (ItemStack in : box.getInventory().getContents()) collectItem(in, out, depth + 1);
        }
    }

    // ---------------------------------------------------------------- damga temizleme

    /** Kimlik damgasını (shulker içindekiler dahil) kaldırır. Creative ve geri verme için. */
    public ItemStack strip(ItemStack item) {
        if (isAir(item)) return item;
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        boolean changed = false;
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        if (pdc.has(key, PersistentDataType.STRING)) {
            pdc.remove(key);
            changed = true;
        }
        ShulkerBox box = boxOf(item, m);
        if (box != null) {
            Inventory in = box.getInventory();
            ItemStack[] arr = in.getContents();
            boolean any = false;
            for (int i = 0; i < arr.length; i++) {
                if (isAir(arr[i])) continue;
                in.setItem(i, strip(arr[i]));
                any = true;
            }
            if (any) {
                ((BlockStateMeta) m).setBlockState(box);
                changed = true;
            }
        }
        if (changed) item.setItemMeta(m);
        return item;
    }

    // ---------------------------------------------------------------- tarama (değiştirir)

    private class Ctx {
        final Player player;
        final String reason;
        final Set<UUID> seen = new HashSet<>();
        final Map<UUID, Set<UUID>> others = new HashMap<>();
        final List<String> removed = new ArrayList<>();
        boolean modified;

        Ctx(Player player, String reason) {
            this.player = player;
            this.reason = reason;
        }

        void quarantine(ItemStack it, String why) {
            int id = plugin.getQuarantine().add(it, player.getUniqueId(), player.getName(), why + "/" + reason);
            removed.add("#" + id + " " + it.getType().name() + "x" + it.getAmount() + " (" + why + ")");
        }
    }

    /** Oyuncunun envanter + ender chest + imleç eşyalarını tarar. Karantinaya alınan eşya sayısını döner. */
    public int scan(Player p, String reason) {
        if (!p.isOnline() || p.hasPermission("antidupe.bypass") || p.getGameMode() == GameMode.CREATIVE) return 0;
        Ctx c = new Ctx(p, reason);

        scanInventory(p.getInventory(), c);
        scanInventory(p.getEnderChest(), c);

        ItemStack cur = p.getItemOnCursor();
        if (!isAir(cur)) {
            c.modified = false;
            ItemStack r = process(cur, c);
            if (c.modified || r != cur) p.setItemOnCursor(r);
        }

        if (!c.removed.isEmpty()) {
            p.sendMessage(plugin.msg("quarantined-player", c.removed.size()));
            plugin.alert(plugin.rawMsg("alert-scan", p.getName(), c.removed.size(), reason,
                    String.join(", ", c.removed)));
        }
        if (holders.size() > 300_000) holders.clear();
        return c.removed.size();
    }

    private void scanInventory(Inventory inv, Ctx c) {
        ItemStack[] arr = inv.getContents();
        for (int i = 0; i < arr.length; i++) {
            ItemStack it = arr[i];
            if (isAir(it)) continue;
            c.modified = false;
            ItemStack r = process(it, c);
            if (c.modified || r != it) inv.setItem(i, r);
        }
    }

    private boolean duplicateElsewhere(UUID id, Ctx c) {
        if (!plugin.getConfig().getBoolean("settings.cross-player-detection", true)) return false;
        UUID owner = holders.get(id);
        if (owner == null || owner.equals(c.player.getUniqueId())) return false;
        Player op = Bukkit.getPlayer(owner);
        if (op == null || !op.isOnline() || op.getGameMode() == GameMode.CREATIVE) return false;
        // Doğrulama: eski sahip eşyayı GERÇEKTEN hâlâ taşıyor mu?
        return c.others.computeIfAbsent(owner, k -> collect(op)).contains(id);
    }

    /** @return işlenmiş eşya; null ise eşya karantinaya alındı ve slot boşaltılmalı. */
    private ItemStack process(ItemStack item, Ctx c) {
        if (isAir(item)) return item;

        // 1) Overstack: fazlalığı karantinaya al, geri kalanı bırak
        int max = Math.max(1, item.getMaxStackSize());
        if (item.getAmount() > max) {
            ItemStack extra = item.clone();
            extra.setAmount(item.getAmount() - max);
            c.quarantine(extra, "OVERSTACK");
            item.setAmount(max);
            c.modified = true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        boolean metaChanged = false;

        // 2) Benzersiz kimlik damgası + kopya tespiti
        if (isTracked(item)) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            UUID id = parse(pdc.get(key, PersistentDataType.STRING));
            if (id == null) {
                id = UUID.randomUUID();
                pdc.set(key, PersistentDataType.STRING, id.toString());
                metaChanged = true;
            }
            if (!c.seen.add(id) || duplicateElsewhere(id, c)) {
                c.quarantine(item, "DUPLICATE");
                c.modified = true;
                return null;
            }
            holders.put(id, c.player.getUniqueId());
        }

        // 3) Shulker içeriği
        ShulkerBox box = boxOf(item, meta);
        if (box != null) {
            Inventory in = box.getInventory();
            ItemStack[] arr = in.getContents();
            boolean inChanged = false;
            for (int i = 0; i < arr.length; i++) {
                ItemStack it = arr[i];
                if (isAir(it)) continue;
                if (isShulker(it.getType())) {
                    c.quarantine(it, "NESTED_SHULKER");
                    in.setItem(i, null);
                    inChanged = true;
                    continue;
                }
                boolean prev = c.modified;
                c.modified = false;
                ItemStack r = process(it, c);
                if (c.modified || r != it) {
                    in.setItem(i, r);
                    inChanged = true;
                }
                c.modified = prev;
            }
            if (inChanged) {
                ((BlockStateMeta) meta).setBlockState(box);
                metaChanged = true;
            }
        }

        if (metaChanged) {
            item.setItemMeta(meta);
            c.modified = true;
        }
        return item;
    }
}
