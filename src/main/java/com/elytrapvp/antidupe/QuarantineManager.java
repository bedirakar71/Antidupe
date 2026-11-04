package com.elytrapvp.antidupe;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Silinen her eşya burada saklanır. Yanlış silme olursa geri verilebilir.
 */
public class QuarantineManager {

    public static class Entry {
        public final int id;
        public final ItemStack item;
        public final UUID player;
        public final String playerName;
        public final String reason;
        public final long time;
        public boolean restored;

        Entry(int id, ItemStack item, UUID player, String playerName, String reason, long time, boolean restored) {
            this.id = id;
            this.item = item;
            this.player = player;
            this.playerName = playerName;
            this.reason = reason;
            this.time = time;
            this.restored = restored;
        }
    }

    private final AntiDupe plugin;
    private final File file;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final Object ioLock = new Object();
    private int nextId = 1;
    private boolean dirty;

    public QuarantineManager(AntiDupe plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "quarantine.yml");
    }

    public synchronized void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next-id", 1);
        ConfigurationSection s = y.getConfigurationSection("entries");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                ConfigurationSection e = s.getConfigurationSection(key);
                if (e == null) continue;
                ItemStack item = e.getItemStack("item");
                if (item == null) continue;
                int id = Integer.parseInt(key);
                String pu = e.getString("player");
                UUID uuid = pu == null ? null : UUID.fromString(pu);
                entries.put(id, new Entry(id, item, uuid, e.getString("name", "?"),
                        e.getString("reason", "?"), e.getLong("time"), e.getBoolean("restored")));
                if (id >= nextId) nextId = id + 1;
            } catch (Exception ex) {
                plugin.getLogger().warning("Karantina kaydı okunamadı: " + key + " (" + ex.getMessage() + ")");
            }
        }
    }

    public synchronized int add(ItemStack item, UUID player, String name, String reason) {
        Entry e = new Entry(nextId++, item.clone(), player, name == null ? "WORLD" : name,
                reason, System.currentTimeMillis(), false);
        entries.put(e.id, e);
        dirty = true;
        return e.id;
    }

    public synchronized Entry get(int id) {
        return entries.get(id);
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public synchronized List<Entry> byPlayer(String name) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.playerName.equalsIgnoreCase(name)) out.add(e);
        }
        return out;
    }

    /** Eşyayı hedef oyuncuya verir (kimlik damgası temizlenir, böylece tekrar "dupe" sayılmaz). */
    public synchronized boolean restore(Entry e, Player target) {
        if (e.restored) return false;
        ItemStack give = plugin.getGuard().strip(e.item.clone());
        Map<Integer, ItemStack> left = target.getInventory().addItem(give);
        for (ItemStack l : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), l);
        }
        e.restored = true;
        dirty = true;
        return true;
    }

    public synchronized int purge(long days) {
        long limit = System.currentTimeMillis() - days * 86_400_000L;
        int n = 0;
        java.util.Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.restored && e.time < limit) {
                it.remove();
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    private synchronized String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Entry e : entries.values()) {
            String p = "entries." + e.id + ".";
            y.set(p + "item", e.item);
            y.set(p + "player", e.player == null ? null : e.player.toString());
            y.set(p + "name", e.playerName);
            y.set(p + "reason", e.reason);
            y.set(p + "time", e.time);
            y.set(p + "restored", e.restored);
        }
        return y.saveToString();
    }

    public void saveAsync() {
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
        }
        final String data = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveNow() {
        synchronized (this) {
            dirty = false;
        }
        write(serialize());
    }

    private void write(String data) {
        synchronized (ioLock) {
            try {
                Path tmp = file.toPath().resolveSibling("quarantine.yml.tmp");
                Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().severe("Karantina kaydedilemedi: " + ex.getMessage());
            }
        }
    }
}
