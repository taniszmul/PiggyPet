package pl.twojnick.piggypet;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class PiggyPet extends JavaPlugin implements CommandExecutor, Listener {

    private NamespacedKey ownerKey;
    private final Map<UUID, Inventory> pigInventories = new HashMap<>();
    private final Map<UUID, Pig> activePigs = new HashMap<>();
    private final Set<UUID> frozenPigs = new HashSet<>();
    private final Map<UUID, Long> lastPlayerActivity = new HashMap<>();

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "pig_owner");

        if (getCommand("swinia") != null) getCommand("swinia").setExecutor(this);
        if (getCommand("pomoc") != null) getCommand("pomoc").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        // Główna pętla zadania: poruszanie się, obrót głowy, sprawdzanie zdrowia oraz AFK
        new PigTickTask().runTaskTimer(this, 1L, 2L);

        // Zapisywanie awaryjne ekwipunków co 3 minuty
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : pigInventories.keySet()) {
                savePigInventory(uuid);
            }
        }, 3600L, 3600L);

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
            sender.sendMessage("Komenda tylko dla graczy!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("pomoc")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("swinia")) {
                sendHelpMenu(player);
                return true;
            }
            return false;
        }

        if (args.length == 0) {
            sendHelpMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("spawn") || sub.equals("przywolaj")) {
            if (player.getHealth() <= 10.0) {
                player.sendMessage(ChatColor.RED + "Masz za mało zdrowia (5 serduszek lub mniej)! Jest zbyt niebezpiecznie, aby przywołać świnię.");
                return true;
            }

            if (activePigs.containsKey(player.getUniqueId()) && activePigs.get(player.getUniqueId()).isValid()) {
                Pig pig = activePigs.get(player.getUniqueId());
                pig.teleport(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Przywołano Twoją świnię do Twojej pozycji!");
                return true;
            }

            Pig pig = player.getWorld().spawn(player.getLocation(), Pig.class);
            updatePigName(pig, player, "Świnia");

            pig.setCustomNameVisible(true);
            pig.setInvulnerable(true);
            pig.setAgeLock(true);
            pig.setSilent(true);
            pig.setCollidable(false);

            pig.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());

            activePigs.put(player.getUniqueId(), pig);
            lastPlayerActivity.put(player.getUniqueId(), System.currentTimeMillis());
            loadPigInventory(player.getUniqueId());

            player.sendMessage(ChatColor.GREEN + "Zespawnowano Twoją świnię!");
            return true;
        }

        if (sub.equals("usun") || sub.equals("schowaj") || sub.equals("despawn")) {
            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie!");
                return true;
            }

            despawnPigWithSmoke(player.getUniqueId(), pig, ChatColor.YELLOW + "Świnia została schowana. Przedmioty są bezpieczne!");
            return true;
        }

        if (sub.equals("stoj") || sub.equals("stop")) {
            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie!");
                return true;
            }

            if (frozenPigs.contains(player.getUniqueId())) {
                frozenPigs.remove(player.getUniqueId());
                pig.getChunk().setForceLoaded(false);
                player.sendMessage(ChatColor.GREEN + "Świnia znowu za Tobą chodzi!");
            } else {
                frozenPigs.add(player.getUniqueId());
                pig.getChunk().setForceLoaded(true);
                player.sendMessage(ChatColor.GOLD + "Świnia stoi w miejscu i utrzymuje ten chunk w pamięci!");
            }
            return true;
        }

        if (sub.equals("nazwa")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia nazwa <nowa_nazwa>");
                return true;
            }

            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie!");
                return true;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }

            updatePigName(pig, player, sb.toString().trim());
            player.sendMessage(ChatColor.GREEN + "Zmieniono nazwę świni!");
            return true;
        }

        return true;
    }

    private void sendHelpMenu(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== OPIS KOMEND SYSTEMU ŚWINIA ===");
        player.sendMessage(ChatColor.YELLOW + "/swinia spawn " + ChatColor.WHITE + "- Spawnuje/przywołuje świnię do ciebie.");
        player.sendMessage(ChatColor.YELLOW + "/swinia schowaj " + ChatColor.WHITE + "- Chowa świnię bez utraty przedmiotów.");
        player.sendMessage(ChatColor.YELLOW + "/swinia stoj " + ChatColor.WHITE + "- Zatrzymuje świnię w miejscu (ładuje chunk).");
        player.sendMessage(ChatColor.YELLOW + "/swinia nazwa <tekst> " + ChatColor.WHITE + "- Zmienia dopisek w nazwie świni.");
    }

    private void updatePigName(Pig pig, Player owner, String customSuffix) {
        String formattedSuffix = ChatColor.translateAlternateColorCodes('&', customSuffix);
        pig.setCustomName(ChatColor.BOLD + "" + ChatColor.GOLD + "[" + owner.getName() + "] " + ChatColor.RESET + formattedSuffix);
    }

    private void despawnPigWithSmoke(UUID ownerUuid, Pig pig, String reasonMessage) {
        savePigInventory(ownerUuid);
        Location loc = pig.getLocation().add(0, 0.5, 0);

        // Efekt dymu i dźwięk znikania
        pig.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 25, 0.3, 0.5, 0.3, 0.05);
        pig.getWorld().spawnParticle(Particle.SMOKE, loc, 20, 0.2, 0.4, 0.2, 0.02);
        pig.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);

        pig.remove();
        activePigs.remove(ownerUuid);
        frozenPigs.remove(ownerUuid);

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner != null && owner.isOnline() && reasonMessage != null) {
            owner.sendMessage(reasonMessage);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockY() != event.getTo().getBlockY() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            lastPlayerActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastPlayerActivity.put(player.getUniqueId(), System.currentTimeMillis());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!activePigs.containsKey(player.getUniqueId()) || !activePigs.get(player.getUniqueId()).isValid()) {
                player.sendMessage(ChatColor.GREEN + "----------------------------------------------------");
                player.sendMessage(ChatColor.YELLOW + "Hej! Masz przenośny plecak w postaci świni.");
                player.sendMessage(ChatColor.YELLOW + "Wpisz komendę " + ChatColor.GREEN + "'/swinia spawn'" + ChatColor.YELLOW + " a pojawi się Twoja świnia.");
                player.sendMessage(ChatColor.YELLOW + "Wpisz " + ChatColor.AQUA + "'/pomoc swinia'" + ChatColor.YELLOW + " aby zobaczyć dokładnie opisane komendy.");
                player.sendMessage(ChatColor.GREEN + "----------------------------------------------------");
            }
        }, 40L);
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

            if (!player.getUniqueId().equals(ownerUuid)) {
                player.sendMessage(ChatColor.RED + "To nie jest Twoja świnia! Nie masz dostępu do jej ekwipunku.");
                return;
            }

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
        if (event.getEntity() instanceof Pig pig && pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPigHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Pig pig && pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    private void loadExistingPigs() {
        Bukkit.getWorlds().forEach(world -> {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Pig pig && pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                    String uuidStr = pig.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    if (uuidStr != null) {
                        UUID ownerUuid = UUID.fromString(uuidStr);
                        activePigs.put(ownerUuid, pig);
                        pig.setInvulnerable(true);
                        pig.setSilent(true);
                        pig.setCollidable(false);
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

    private class PigTickTask extends BukkitRunnable {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            for (var entry : new HashMap<>(activePigs).entrySet()) {
                UUID ownerUuid = entry.getKey();
                Pig pig = entry.getValue();

                if (pig == null || !pig.isValid()) continue;

                Player owner = Bukkit.getPlayer(ownerUuid);
                if (owner == null || !owner.isOnline() || !owner.getWorld().equals(pig.getWorld())) continue;

                // 1. Sprawdzanie zdrowia gracza (5 serduszek = 10 HP lub mniej)
                if (owner.getHealth() <= 10.0) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Jest zbyt niebezpiecznie (masz 5 serduszek lub mniej)! Twoja świnia zniknęła w bezpieczne miejsce.");
                    continue;
                }

                // 2. Sprawdzanie braku aktywności (AFK > 10 minut = 600,000 ms)
                long lastAct = lastPlayerActivity.getOrDefault(ownerUuid, now);
                if (now - lastAct > 600000L) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.YELLOW + "Byłeś/aś AFK przez ponad 10 minut! Twoja świnia schowała się bezpiecznie.");
                    continue;
                }

                // 3. Ciągły obrót głowy w stronę gracza
                Location pigLoc = pig.getLocation();
                Location ownerLoc = owner.getLocation();

                Vector direction = ownerLoc.toVector().subtract(pigLoc.toVector());
                if (direction.lengthSquared() > 0) {
                    pigLoc.setDirection(direction);
                    pig.teleport(pigLoc);
                }

                if (frozenPigs.contains(ownerUuid)) continue;

                // 4. Poruszanie się z zachowaniem bezpiecznego dystansu 2.2 kratek
                double distance = pigLoc.distance(ownerLoc);

                if (distance > 20.0) {
                    pig.teleport(ownerLoc);
                } else if (distance > 2.2) {
                    pig.getPathfinder().moveTo(ownerLoc, 1.25);
                } else {
                    pig.getPathfinder().stop();
                }
            }
        }
    }
}
