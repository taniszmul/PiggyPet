package pl.twojnick.piggypet;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
    private final Map<UUID, Location> frozenPigs = new HashMap<>();
    private final Map<UUID, Long> lastPlayerActivity = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> autoRespawnDisabled = new HashSet<>();

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "pig_owner");

        if (getCommand("swinia") != null) getCommand("swinia").setExecutor(this);
        if (getCommand("pomoc") != null) getCommand("pomoc").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        new PigTickTask().runTaskTimer(this, 1L, 2L);

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
        // Czyszczenie ew. stojaków na grzyby przy wyłączaniu
        for (Pig pig : activePigs.values()) {
            if (pig != null && pig.isValid()) {
                removeMushroomHat(pig);
            }
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

        if (sub.equals("podglad")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Ta komenda jest dostępna tylko dla operatorów (OP)!");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia podglad <nick>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();

            Inventory targetInv = getOrCreatePigInventory(targetUuid);
            player.openInventory(targetInv);
            player.sendMessage(ChatColor.GOLD + "[OP] Otworzono ekwipunek świni gracza " + args[1]);
            return true;
        }

        if (sub.equals("reset")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Ta komenda jest dostępna tylko dla operatorów (OP)!");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia reset <nick>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();

            if (activePigs.containsKey(targetUuid) && activePigs.get(targetUuid).isValid()) {
                Pig pig = activePigs.get(targetUuid);
                removeMushroomHat(pig);
                pig.remove();
                activePigs.remove(targetUuid);
            }
            pigInventories.remove(targetUuid);
            frozenPigs.remove(targetUuid);
            cooldownUntil.remove(targetUuid);

            File file = new File(getDataFolder() + "/inventories/", targetUuid.toString() + ".yml");
            if (file.exists()) {
                file.delete();
            }

            player.sendMessage(ChatColor.GREEN + "[OP] Zresetowano stan i usunięto dane świni gracza " + args[1]);
            return true;
        }

        if (sub.equals("autorespawn")) {
            if (autoRespawnDisabled.contains(player.getUniqueId())) {
                autoRespawnDisabled.remove(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Automatyczny respawn świni po śmierci został WŁĄCZONY.");
            } else {
                autoRespawnDisabled.add(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Automatyczny respawn świni po śmierci został WYŁĄCZONY.");
            }
            return true;
        }

        if (sub.equals("spawn") || sub.equals("przywolaj")) {
            if (player.getGameMode() != GameMode.SURVIVAL) {
                player.sendMessage(ChatColor.RED + "Możesz używać świni tylko w trybie SURVIVAL!");
                return true;
            }

            if (player.getHealth() <= 10.0) {
                player.sendMessage(ChatColor.RED + "Masz za mało zdrowia (5 serduszek lub mniej)! Jest zbyt niebezpiecznie, aby przywołać świnię.");
                return true;
            }

            if (cooldownUntil.containsKey(player.getUniqueId())) {
                long remaining = (cooldownUntil.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000;
                if (remaining > 0) {
                    player.sendMessage(ChatColor.RED + "Twoja świnia uciekła! Musisz odczekać jeszcze " + remaining + " sekund przed ponownym przyzwaniem.");
                    return true;
                } else {
                    cooldownUntil.remove(player.getUniqueId());
                }
            }

            if (activePigs.containsKey(player.getUniqueId()) && activePigs.get(player.getUniqueId()).isValid()) {
                Pig pig = activePigs.get(player.getUniqueId());
                pig.teleport(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Przywołano Twoją świnię do Twojej pozycji!");
                return true;
            }

            spawnPigForPlayer(player);
            player.sendMessage(ChatColor.GREEN + "Zespawnowano Twoją świnię!");
            return true;
        }

        if (sub.equals("usun") || sub.equals("schowaj") || sub.equals("despawn")) {
            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie!");
                return true;
            }

            despawnPigWithSmoke(player.getUniqueId(), pig, ChatColor.YELLOW + "Świnia została schowana. Przedmioty są bezpieczne!", false);
            return true;
        }

        if (sub.equals("stoj") || sub.equals("stop")) {
            Pig pig = activePigs.get(player.getUniqueId());
            if (pig == null || !pig.isValid()) {
                player.sendMessage(ChatColor.RED + "Nie masz aktywnej świni na świecie!");
                return true;
            }

            if (frozenPigs.containsKey(player.getUniqueId())) {
                frozenPigs.remove(player.getUniqueId());
                pig.getChunk().setForceLoaded(false);
                player.sendMessage(ChatColor.GREEN + "Świnia znowu za Tobą chodzi!");
            } else {
                Location currentLoc = pig.getLocation();
                frozenPigs.put(player.getUniqueId(), currentLoc);
                pig.getChunk().setForceLoaded(true);
                player.sendMessage(ChatColor.GOLD + "Świnia zostaje w tym miejscu (promień 3 bloków) i utrzymuje chunk!");
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

    private void spawnPigForPlayer(Player player) {
        Pig pig = player.getWorld().spawn(player.getLocation(), Pig.class);
        updatePigName(pig, player, "Świnia");

        pig.setCustomNameVisible(true);
        pig.setInvulnerable(true);
        pig.setAgeLock(true);
        pig.setSilent(true);
        pig.setCollidable(false);
        pig.setSaddle(false);

        pig.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());

        // Dodanie czapki z grzyba
        attachMushroomHat(pig);

        activePigs.put(player.getUniqueId(), pig);
        lastPlayerActivity.put(player.getUniqueId(), System.currentTimeMillis());
        loadPigInventory(player.getUniqueId());
    }

    private void attachMushroomHat(Pig pig) {
        removeMushroomHat(pig); // Upewniamy się, że nie ma starej czapki

        ArmorStand stand = pig.getWorld().spawn(pig.getLocation(), ArmorStand.class);
        stand.setVisible(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        if (stand.getEquipment() != null) {
            stand.getEquipment().setHelmet(new ItemStack(Material.RED_MUSHROOM));
        }

        pig.addPassenger(stand);
    }

    private void removeMushroomHat(Pig pig) {
        for (Entity passenger : new ArrayList<>(pig.getPassengers())) {
            if (passenger instanceof ArmorStand) {
                passenger.remove();
            }
        }
    }

    private void sendHelpMenu(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== OPIS KOMEND SYSTEMU ŚWINIA ===");
        player.sendMessage(ChatColor.YELLOW + "/swinia spawn " + ChatColor.WHITE + "- Spawnuje/przywołuje świnię do Ciebie (tylko Survival).");
        player.sendMessage(ChatColor.YELLOW + "/swinia schowaj " + ChatColor.WHITE + "- Chowa świnię bez utraty przedmiotów.");
        player.sendMessage(ChatColor.YELLOW + "/swinia stoj " + ChatColor.WHITE + "- Zatrzymuje świnię w promieniu 3 bloków.");
        player.sendMessage(ChatColor.YELLOW + "/swinia nazwa <tekst> " + ChatColor.WHITE + "- Zmienia dopisek w nazwie świni.");
        player.sendMessage(ChatColor.YELLOW + "/swinia autorespawn " + ChatColor.WHITE + "- Włącza/wyłącza automatyczne respawnowanie świni po Twojej śmierci.");
        if (player.isOp()) {
            player.sendMessage(ChatColor.RED + "[OP] /swinia podglad <nick> " + ChatColor.WHITE + "- Podgląd ekwipunku świni gracza.");
            player.sendMessage(ChatColor.RED + "[OP] /swinia reset <nick> " + ChatColor.WHITE + "- Całkowity reset świni i danych gracza.");
        }
    }

    private void updatePigName(Pig pig, Player owner, String customSuffix) {
        String formattedSuffix = ChatColor.translateAlternateColorCodes('&', customSuffix);
        pig.setCustomName(ChatColor.BOLD + "" + ChatColor.GOLD + "[" + owner.getName() + "] " + ChatColor.RESET + formattedSuffix);
    }

    private void despawnPigWithSmoke(UUID ownerUuid, Pig pig, String reasonMessage, boolean triggerCooldown) {
        savePigInventory(ownerUuid);
        Location loc = pig.getLocation().add(0, 0.5, 0);

        pig.getWorld().spawnParticle(Particle.SMOKE_LARGE, loc, 25, 0.3, 0.5, 0.3, 0.05);
        pig.getWorld().spawnParticle(Particle.SMOKE_NORMAL, loc, 20, 0.2, 0.4, 0.2, 0.02);
        pig.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);

        removeMushroomHat(pig);
        pig.remove();
        activePigs.remove(ownerUuid);
        frozenPigs.remove(ownerUuid);

        if (triggerCooldown) {
            cooldownUntil.put(ownerUuid, System.currentTimeMillis() + 60000L);
        }

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner != null && owner.isOnline() && reasonMessage != null) {
            owner.sendMessage(reasonMessage);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (autoRespawnDisabled.contains(player.getUniqueId())) return;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.getGameMode() == GameMode.SURVIVAL && player.getHealth() > 10.0) {
                if (!cooldownUntil.containsKey(player.getUniqueId()) || System.currentTimeMillis() >= cooldownUntil.get(player.getUniqueId())) {
                    if (!activePigs.containsKey(player.getUniqueId()) || !activePigs.get(player.getUniqueId()).isValid()) {
                        spawnPigForPlayer(player);
                        player.sendMessage(ChatColor.GREEN + "Twoja świnia odrodziła się wraz z Tobą!");
                    }
                }
            }
        }, 20L);
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
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Pig pig)) return;

        if (pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true); // Anuluje jeżdżenie, zakładanie siodła itp.

            if (event.getHand() != EquipmentSlot.HAND) return;

            Player player = event.getPlayer();
            String ownerUuidStr = pig.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerUuidStr == null) return;

            UUID ownerUuid = UUID.fromString(ownerUuidStr);

            if (!player.getUniqueId().equals(ownerUuid) && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "To nie jest Twoja świnia! Nie masz dostępu do jej ekwipunku.");
                return;
            }

            Inventory pigInv = getOrCreatePigInventory(ownerUuid);
            player.openInventory(pigInv);
        }
    }

    @EventHandler
    public void onPigBreed(EntityBreedEvent event) {
        if (event.getEntity() instanceof Pig pig && pig.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
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
                        attachMushroomHat(pig);
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
                if (owner == null || !owner.isOnline()) continue;

                if (owner.getGameMode() != GameMode.SURVIVAL) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Świnia znika, ponieważ nie jesteś w trybie SURVIVAL!", false);
                    continue;
                }

                if (owner.getHealth() <= 10.0) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Jest zbyt niebezpiecznie (masz 5 serduszek lub mniej)! Twoja świnia uciekła. Musisz odczekać 1 minutę przed ponownym przyzwaniem.", true);
                    continue;
                }

                long lastAct = lastPlayerActivity.getOrDefault(ownerUuid, now);
                if (now - lastAct > 600000L) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.YELLOW + "Byłeś/aś AFK przez ponad 10 minut! Twoja świnia schowała się bezpiecznie.", false);
                    continue;
                }

                // Zapewnienie istnienia czapeczki-grzyba
                if (pig.getPassengers().isEmpty()) {
                    attachMushroomHat(pig);
                }

                Location pigLoc = pig.getLocation();
                Location ownerLoc = owner.getLocation();

                // SPRAWDZENIE TELEPORTACJI (Różne światy LUB dystans > 20 bloków)
                boolean isDifferentWorld = !pigLoc.getWorld().equals(ownerLoc.getWorld());
                boolean isTooFar = !isDifferentWorld && pigLoc.distance(ownerLoc) > 20.0;

                if (!frozenPigs.containsKey(ownerUuid)) {
                    if (isDifferentWorld || isTooFar) {
                        pig.teleport(ownerLoc);
                        continue;
                    }
                } else {
                    // Obsługa zatrzymania
                    Location anchorLoc = frozenPigs.get(ownerUuid);
                    if (pigLoc.getWorld().equals(anchorLoc.getWorld()) && pigLoc.distance(anchorLoc) > 3.0) {
                        Vector backToAnchor = anchorLoc.toVector().subtract(pigLoc.toVector()).normalize().multiply(0.2);
                        pigLoc.add(backToAnchor);
                        pig.teleport(pigLoc);
                    }
                    continue;
                }

                // CHODZENIE ZA GRACZEM
                Vector direction = ownerLoc.toVector().subtract(pigLoc.toVector());

                if (direction.lengthSquared() > 0) {
                    pigLoc.setDirection(direction);
                }

                double distance = pigLoc.distance(ownerLoc);

                if (distance > 2.2) {
                    Vector moveVec = direction.normalize().multiply(0.25);
                    pigLoc.add(moveVec);

                    int highestY = pigLoc.getWorld().getHighestBlockYAt(pigLoc);
                    if (pigLoc.getY() < highestY + 1.0) {
                        pigLoc.setY(highestY + 1.0);
                    }

                    pig.teleport(pigLoc);
                }
            }
        }
    }
}
