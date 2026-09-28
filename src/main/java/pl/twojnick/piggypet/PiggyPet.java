package pl.twojnick.piggypet;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PiggyPet extends JavaPlugin implements CommandExecutor, Listener {

    private NamespacedKey ownerKey;
    private final Map<UUID, Inventory> pigInventories = new HashMap<>();
    private final Map<UUID, Pig> activePigs = new HashMap<>();

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "pig_owner");

        if (getCommand("swinia") != null) {
            getCommand("swinia").setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        new PigFollowTask().runTaskTimer(this, 20L, 10L);
        Bukkit.getScheduler().runTaskLater(this, this::loadExistingPigs, 40L);
    }

    @Override
    public void onDisable() {
        for (UUID ownerUuid : pigInventories.keySet()) {
            savePigInventory(ownerUuid);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest tylko dla graczy!");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.GOLD + "=== System PiggyPet ===");
            player.sendMessage(ChatColor.YELLOW + "/swinia spawn " + ChatColor.WHITE + "- Spawnuje Twoją świnię");
            player.sendMessage(ChatColor.YELLOW + "/swinia nazwa <tekst> " + ChatColor.WHITE + "- Zmienia nazwę świni");
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (activePigs.containsKey(player.getUniqueId()) && activePigs.get(player.getUniqueId()).isValid()) {
                player.sendMessage(ChatColor.RED + "Masz już zespawnowaną świnię na świecie!");
                return true;
            }

            Pig pig = player.getWorld().spawn(player.getLocation(), Pig.class);
            pig.setCustomName(ChatColor.GREEN + "Świnia gracza " + player.getName());
            pig.setCustomNameVisible(true);
            pig.setInvulnerable(true);
            pig.setAgeLock(true);

            pig.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());

            activePigs.put(player.getUniqueId(), pig);
            loadPigInventory(player.getUniqueId());

            player.sendMessage(ChatColor.GREEN + "Pomyślnie zespawnowano Twoją świnię!");
            return true;
        }

        if (args[0].equalsIgnoreCase("nazwa")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia nazwa <nowa_nazwa>");
                return true;
            }

            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie! Zespawnuj ją przez /swinia spawn");
                return true;
            }

            StringBuilder newName = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                newName.append(args[i]).append(" ");
            }

            String formattedName = ChatColor.translateAlternateColorCodes('&', newName.toString().trim());
            pig.setCustomName(formattedName);
            player.sendMessage(ChatColor.GREEN + "Zmieniono nazwę świni na: " + formattedName);
            return true;
        }

        return true;
    }

    @EventHandler
    public void onPigInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity entity = event.getRightClicked();
        if (!(entity instanceof Pig pig)) return;

        if (pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
            Player player = event.getPlayer();

            String ownerUuidStr = pig.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerUuidStr == null) return;

            UUID ownerUuid = UUID.fromString(ownerUuidStr);
            Inventory pigInv = getOrCreatePigInventory(ownerUuid);

            player.openInventory(pigInv);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        for (var entry : pigInventories.entrySet()) {
            if (entry.getValue().equals(event.getInventory())) {
                savePigInventory(entry.getKey());
                break;
            }
        }
    }

    @EventHandler
    public void onPigDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Pig pig) {
            if (pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPigHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Pig pig) {
            if (pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                event.setCancelled(true);
            }
        }
    }

    private void loadExistingPigs() {
        Bukkit.getWorlds().forEach(world -> {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Pig pig) {
                    if (pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                        String uuidStr = pig.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                        if (uuidStr != null) {
                            UUID ownerUuid = UUID.fromString(uuidStr);
                            activePigs.put(ownerUuid, pig);
                            pig.setInvulnerable(true);
                        }
                    }
                }
            }
        });
    }

    public Inventory getOrCreatePigInventory(UUID ownerUuid) {
        if (!pigInventories.containsKey(ownerUuid)) {
            loadPigInventory(ownerUuid);
        }
        return pigInventories.get(ownerUuid);
    }

    public void savePigInventory(UUID ownerUuid) {
        Inventory inv = pigInventories.get(ownerUuid);
        if (inv == null) return;

        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        for (int i = 0; i < inv.getSize(); i++) {
            config.set("slot." + i, inv.getItem(i));
        }

        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadPigInventory(UUID ownerUuid) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Ekwipunek Świnki");
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");

        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            for (int i = 0; i < inv.getSize(); i++) {
                if (config.contains("slot." + i)) {
                    inv.setItem(i, config.getItemStack("slot." + i));
                }
            }
        }
        pigInventories.put(ownerUuid, inv);
    }

    private class PigFollowTask extends BukkitRunnable {
        @Override
        public void run() {
            for (var entry : activePigs.entrySet()) {
                UUID ownerUuid = entry.getKey();
                Pig pig = entry.getValue();

                if (pig == null || !pig.isValid()) continue;

                Player owner = Bukkit.getPlayer(ownerUuid);

                if (owner != null && owner.isOnline() && owner.getWorld().equals(pig.getWorld())) {
                    double distance = pig.getLocation().distance(owner.getLocation());

                    if (distance > 20.0) {
                        pig.teleport(owner.getLocation());
                    } else if (distance > 2.5) {
                        Location target = owner.getLocation();
                        pig.getPathfinder().moveTo(target, 1.25);
                    }
                }
            }
        }
    }
}
