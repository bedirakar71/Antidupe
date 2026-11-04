package com.elytrapvp.antidupe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DupeListener implements Listener {

    private static class Rate {
        long start;
        int count;
        int violations;
        long lastViolation;
    }

    private final AntiDupe plugin;
    private final ItemGuard guard;
    private final Map<UUID, Rate> rates = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> flagged = new HashSet<>();

    private int maxClicks;
    private int violationLimit;
    private long scanDelay;
    private boolean blockCreative;
    private boolean kickModded;
    private List<String> moddedPrefixes;
    private Set<String> containerCmds;

    public DupeListener(AntiDupe plugin) {
        this.plugin = plugin;
        this.guard = plugin.getGuard();
    }

    public void reload() {
        maxClicks = plugin.getConfig().getInt("settings.max-clicks-per-second", 40);
        violationLimit = plugin.getConfig().getInt("settings.violations-before-close", 5);
        scanDelay = Math.max(1, plugin.getConfig().getInt("settings.scan-delay-ticks", 5));
        blockCreative = plugin.getConfig().getBoolean("settings.block-creative-containers", true);
        kickModded = "KICK".equalsIgnoreCase(plugin.getConfig().getString("settings.modded-client-action", "FLAG"));
        moddedPrefixes = plugin.getConfig().getStringList("settings.modded-channel-prefixes");
        containerCmds = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("settings.blocked-commands-while-container-open")) {
            containerCmds.add(s.toLowerCase());
        }
    }

    // ------------------------------------------------------------ yardımcılar

    private boolean bypass(Player p) {
        return p.hasPermission("antidupe.bypass");
    }

    private void scanLater(Player p, long delay, String reason) {
        final UUID id = p.getUniqueId();
        if (!pending.add(id)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            if (p.isOnline()) guard.scan(p, reason);
        }, delay);
    }

    private long delayFor(Player p) {
        return flagged.contains(p.getUniqueId()) ? 1L : scanDelay;
    }

    private boolean containerOpen(Player p) {
        InventoryType t = p.getOpenInventory().getTopInventory().getType();
        return t != InventoryType.CRAFTING && t != InventoryType.CREATIVE;
    }

    /** Tıklama hızı sınırı. false = engelle. */
    private boolean allow(Player p) {
        long now = System.currentTimeMillis();
        Rate r = rates.computeIfAbsent(p.getUniqueId(), k -> new Rate());
        if (now - r.start >= 1000) {
            r.start = now;
            r.count = 0;
        }
        int limit = flagged.contains(p.getUniqueId()) ? Math.max(5, maxClicks / 2) : maxClicks;
        if (++r.count <= limit) return true;

        if (now - r.lastViolation > 30_000) r.violations = 0;
        r.lastViolation = now;
        if (++r.violations >= violationLimit) {
            r.violations = 0;
            // DÜZELTİLEN YER (Ambiguous method reference hatasını önlemek için lambda eklendi)
            Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            plugin.alert(plugin.rawMsg("alert-rate", p.getName(), r.count + "/sn"));
            scanLater(p, 1L, "RATE_LIMIT");
        }
        return false;
    }

    // ------------------------------------------------------------ envanter olayları

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (bypass(p)) return;

        if (!allow(p)) {
            e.setCancelled(true);
            return;
        }
        // Creative modunda değilken creative paketi = hile/mod
        if (e.getClick() == ClickType.CREATIVE && p.getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
            plugin.alert(plugin.rawMsg("alert-block", p.getName(), "sahte creative tıklaması"));
            return;
        }
        boolean container = containerOpen(p);
        if (container && p.getGameMode() == GameMode.CREATIVE && blockCreative) {
            e.setCancelled(true);
            p.sendMessage(plugin.msg("creative-blocked"));
            return;
        }
        if (guard.isIllegal(e.getCurrentItem()) || guard.isIllegal(e.getCursor())) {
            e.setCancelled(true);
            plugin.alert(plugin.rawMsg("alert-block", p.getName(), "geçersiz eşya tıklaması"));
            scanLater(p, 1L, "ILLEGAL_CLICK");
            return;
        }
        if (container) scanLater(p, delayFor(p), "CLICK");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (bypass(p)) return;
        if (!allow(p) || guard.isIllegal(e.getOldCursor()) || guard.isIllegal(e.getCursor())) {
            e.setCancelled(true);
            scanLater(p, 1L, "DRAG");
            return;
        }
        if (containerOpen(p)) scanLater(p, delayFor(p), "DRAG");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player) scanLater((Player) e.getPlayer(), 1L, "CLOSE");
    }

    /** Creative'den alınan kopya eşyalar yeni kimlik alsın (aksi halde "dupe" sanılır). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        ItemStack c = e.getCursor();
        if (c != null && c.getType() != org.bukkit.Material.AIR) e.setCursor(guard.strip(c.clone()));
    }

    /** Hopper/dropper ile geçersiz eşya taşınmasını ve shulker→shulker yerleştirmeyi engelle. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        ItemStack it = e.getItem();
        if (guard.isIllegal(it)
                || (e.getDestination().getHolder() instanceof ShulkerBox && ItemGuard.isShulker(it.getType()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (guard.isIllegal(it)) {
            e.setCancelled(true);
            int id = plugin.getQuarantine().add(it, null, "WORLD", "ILLEGAL_HOPPER_PICKUP");
            e.getItem().remove();
            plugin.alert(plugin.rawMsg("alert-block", "WORLD", "hopper geçersiz eşya (#" + id + ")"));
        }
    }

    // ------------------------------------------------------------ yer / drop olayları

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (bypass(p) || p.getGameMode() == GameMode.CREATIVE) return;
        Item entity = e.getItem();
        ItemStack it = entity.getItemStack();

        String why = null;
        if (guard.isIllegal(it)) why = "ILLEGAL_PICKUP";
        else if (guard.isDuplicateFor(p, it)) why = "DUPLICATE_PICKUP";
        if (why == null) return;

        e.setCancelled(true);
        int id = plugin.getQuarantine().add(it, p.getUniqueId(), p.getName(), why);
        entity.remove();
        plugin.alert(plugin.rawMsg("alert-block", p.getName(), why + " → karantina #" + id));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (bypass(p)) return;
        if (guard.isIllegal(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            scanLater(p, 1L, "ILLEGAL_DROP");
        }
    }

    /** Ölüm drop'unda aynı kimlikli eşya iki kez varsa kopyayı karantinaya al. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Set<UUID> seen = new HashSet<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            Set<UUID> ids = guard.idsOf(drop);
            if (ids.isEmpty()) continue;
            boolean dup = false;
            for (UUID id : ids) {
                if (seen.contains(id)) dup = true;
            }
            if (dup) {
                int qid = plugin.getQuarantine().add(drop, p.getUniqueId(), p.getName(), "DEATH_DUPLICATE");
                it.remove();
                plugin.alert(plugin.rawMsg("alert-death", p.getName(), qid));
            } else {
                seen.addAll(ids);
            }
        }
    }

    // ------------------------------------------------------------ oyuncu yaşam döngüsü

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        scanLater(e.getPlayer(), 20L, "JOIN");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Container açıkken çıkış dupe'ünü kapat: önce kapat, sonra tara
        p.closeInventory();
        guard.scan(p, "QUIT");
        UUID id = p.getUniqueId();
        rates.remove(id);
        pending.remove(id);
        flagged.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        scanLater(e.getPlayer(), 10L, "WORLD_CHANGE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        scanLater(e.getPlayer(), 10L, "GAMEMODE");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (bypass(p) || !containerOpen(p)) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (containerCmds.contains(cmd)) {
            e.setCancelled(true);
            p.sendMessage(plugin.msg("command-blocked"));
        }
    }

    // ------------------------------------------------------------ modlu istemci tespiti

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        final Player p = e.getPlayer();
        String ch = e.getChannel().toLowerCase();
        for (String pre : moddedPrefixes) {
            if (!ch.startsWith(pre.toLowerCase())) continue;
            if (flagged.add(p.getUniqueId())) {
                plugin.alert(plugin.rawMsg("alert-modded", p.getName(), e.getChannel()));
                if (kickModded && !bypass(p)) {
                    Bukkit.getScheduler().runTask(plugin, () -> p.kickPlayer(plugin.plain("modded-kick")));
                }
            }
            break;
        }
    }
}
