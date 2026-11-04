package com.elytrapvp.antidupe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AntiDupe extends JavaPlugin implements TabExecutor {

    private QuarantineManager quarantine;
    private ItemGuard guard;
    private DupeListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guard = new ItemGuard(this);
        quarantine = new QuarantineManager(this);
        quarantine.load();
        quarantine.purge(getConfig().getLong("settings.quarantine-retention-days", 30));

        listener = new DupeListener(this);
        listener.reload();
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand cmd = getCommand("antidupe");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Karantinayı düzenli kaydet
        Bukkit.getScheduler().runTaskTimer(this, () -> quarantine.saveAsync(), 1200L, 1200L);

        // Periyodik tam tarama
        long period = getConfig().getLong("settings.periodic-scan-seconds", 60) * 20L;
        if (period > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) guard.scan(p, "PERIODIC");
            }, period, period);
        }
    }

    @Override
    public void onDisable() {
        if (quarantine != null) quarantine.saveNow();
    }

    public QuarantineManager getQuarantine() {
        return quarantine;
    }

    public ItemGuard getGuard() {
        return guard;
    }

    // ------------------------------------------------------------ mesaj yardımcıları

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String fill(String key, Object... args) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** Önekli, renkli mesaj. */
    public String msg(String key, Object... args) {
        return color(getConfig().getString("prefix", "") + fill(key, args));
    }

    /** Önek içermeyen ham metin (alert() için ya da kick mesajı). */
    public String rawMsg(String key, Object... args) {
        return fill(key, args);
    }

    public String plain(String key) {
        return ChatColor.stripColor(color(fill(key)));
    }

    public void alert(String text) {
        String m = color(getConfig().getString("prefix", "") + text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("antidupe.alert")) p.sendMessage(m);
        }
        getLogger().info(ChatColor.stripColor(m));
    }

    // ------------------------------------------------------------ komutlar

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("antidupe.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                guard.reload();
                listener.reload();
                s.sendMessage(msg("reloaded"));
                return true;

            case "list": {
                List<QuarantineManager.Entry> list = a.length > 1 ? quarantine.byPlayer(a[1]) : quarantine.all();
                list = list.stream().filter(e -> !e.restored).collect(Collectors.toList());
                Collections.reverse(list);
                s.sendMessage(color("&7Karantinada &e" + list.size() + " &7kayıt (son 10):"));
                for (int i = 0; i < Math.min(10, list.size()); i++) {
                    QuarantineManager.Entry e = list.get(i);
                    long min = (System.currentTimeMillis() - e.time) / 60000L;
                    s.sendMessage(color("&8#" + e.id + " &f" + e.playerName + " &7" + e.item.getType().name()
                            + "x" + e.item.getAmount() + " &8[&c" + e.reason + "&8] &7" + min + " dk önce"));
                }
                return true;
            }

            case "info": {
                QuarantineManager.Entry e = a.length > 1 ? quarantine.get(parseId(a[1])) : null;
                if (e == null) {
                    s.sendMessage(msg("not-found"));
                    return true;
                }
                s.sendMessage(color("&7#" + e.id + " &f" + e.playerName + " &7| " + e.item.getType().name()
                        + "x" + e.item.getAmount() + " | &c" + e.reason + " &7| geri verildi: " + e.restored));
                return true;
            }

            case "restore": {
                QuarantineManager.Entry e = a.length > 1 ? quarantine.get(parseId(a[1])) : null;
                if (e == null) {
                    s.sendMessage(msg("not-found"));
                    return true;
                }
                if (e.restored) {
                    s.sendMessage(msg("already-restored"));
                    return true;
                }
                Player target = null;
                if (a.length > 2) target = Bukkit.getPlayerExact(a[2]);
                else if (e.player != null) target = Bukkit.getPlayer(e.player);
                if (target == null && a.length <= 2 && s instanceof Player) target = (Player) s;
                if (target == null) {
                    s.sendMessage(msg("no-target"));
                    return true;
                }
                quarantine.restore(e, target);
                s.sendMessage(msg("restored", e.id, target.getName()));
                return true;
            }

            case "restoreall": {
                if (a.length < 2) {
                    help(s);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) {
                    s.sendMessage(msg("no-target"));
                    return true;
                }
                int n = 0;
                for (QuarantineManager.Entry e : quarantine.byPlayer(a[1])) {
                    if (quarantine.restore(e, target)) n++;
                }
                s.sendMessage(color("&a" + n + " kayıt " + target.getName() + " oyuncusuna geri verildi."));
                return true;
            }

            case "scan": {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
                if (target == null) {
                    s.sendMessage(msg("no-target"));
                    return true;
                }
                int n = guard.scan(target, "MANUAL");
                s.sendMessage(color("&7Tarama bitti, karantinaya alınan: &e" + n));
                return true;
            }

            case "purge": {
                long days = a.length > 1 ? parseId(a[1]) : getConfig().getLong("settings.quarantine-retention-days", 30);
                int n = quarantine.purge(Math.max(0, days));
                s.sendMessage(color("&7Geri verilmiş &e" + n + " &7eski kayıt temizlendi."));
                return true;
            }

            default:
                help(s);
                return true;
        }
    }

    private int parseId(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage(color("&c/antidupe &7list [oyuncu] &8- &fkarantinadaki eşyalar"));
        s.sendMessage(color("&c/antidupe &7info <id>"));
        s.sendMessage(color("&c/antidupe &7restore <id> [oyuncu] &8- &fyanlış silineni geri ver"));
        s.sendMessage(color("&c/antidupe &7restoreall <oyuncu>"));
        s.sendMessage(color("&c/antidupe &7scan [oyuncu]"));
        s.sendMessage(color("&c/antidupe &7purge [gün] &8| &7reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!s.hasPermission("antidupe.admin")) return Collections.emptyList();
        if (a.length == 1) {
            return filter(Arrays.asList("list", "info", "restore", "restoreall", "scan", "purge", "reload"), a[0]);
        }
        boolean wantsPlayer = (a.length == 2 && Arrays.asList("list", "restoreall", "scan").contains(a[0].toLowerCase()))
                || (a.length == 3 && a[0].equalsIgnoreCase("restore"));
        if (wantsPlayer) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, a[a.length - 1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> in, String start) {
        return in.stream().filter(x -> x.toLowerCase().startsWith(start.toLowerCase())).collect(Collectors.toList());
    }
                                    }
