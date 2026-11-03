package com.elytrapvp.antidupe;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class UltimateAntiDupe extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey uuidKey;
    private final Set<String> registeredItemUUIDs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger blockedDupeCount = new AtomicInteger(0);

    @Override
    public void onEnable() {
        this.uuidKey = new NamespacedKey(this, "item_unique_id");
        getServer().getPluginManager().registerEvents(this, this);
        
        if (getCommand("antidupe") != null) {
            getCommand("antidupe").setExecutor(this);
        }
        
        getLogger().info("UltimateAntiDupe 1.21.4 (Paper) Aktif!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("antidupe")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                "&8&l[&c&lAntiDupe&8&l] &aAnti dupe şuanda sorunsuz çalışıyor! &7(Engellenen Kopya: " + blockedDupeCount.get() + ")"));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        processItem(event.getCurrentItem());
        processItem(event.getCursor());
        scanInventoryForDupes(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        for (ItemStack item : event.getNewItems().values()) {
            processItem(item);
        }
        scanInventoryForDupes(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            processItem(event.getItem().getItemStack());
            scanInventoryForDupes(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scanInventoryForDupes(event.getPlayer());
    }

    private void processItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !isProtectedItem(item.getType())) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (!pdc.has(uuidKey, PersistentDataType.STRING)) {
            String newUUID = UUID.randomUUID().toString();
            pdc.set(uuidKey, PersistentDataType.STRING, newUUID);
            item.setItemMeta(meta);
            registeredItemUUIDs.add(newUUID);
        }
    }

    private void scanInventoryForDupes(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        Set<String> seenUUIDsInThisScan = new HashSet<>();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR || !isProtectedItem(item.getType())) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            if (pdc.has(uuidKey, PersistentDataType.STRING)) {
                String itemUUID = pdc.get(uuidKey, PersistentDataType.STRING);

                if (seenUUIDsInThisScan.contains(itemUUID)) {
                    player.getInventory().setItem(i, null);
                    blockedDupeCount.incrementAndGet();
                    
                    player.sendMessage(ChatColor.RED + "[AntiDupe] Anormal eşya kopyalama tespit edildi ve kopya eşya silindi!");
                    getLogger().severe("DUPE ENGEL: " + player.getName() + " adlı oyuncuda kopyalanmış eşya (UUID: " + itemUUID + ") silindi!");
                } else {
                    seenUUIDsInThisScan.add(itemUUID);
                }
            } else {
                processItem(item);
            }
        }
    }

    private boolean isProtectedItem(Material type) {
        String name = type.name();
        return name.contains("TOTEM") ||
               name.contains("ELYTRA") ||
               name.contains("CRYSTAL") ||
               name.contains("NETHERITE") ||
               name.contains("DIAMOND_CHESTPLATE") ||
               name.contains("GOLDEN_APPLE") ||
               name.contains("SHULKER_BOX");
    }
}
