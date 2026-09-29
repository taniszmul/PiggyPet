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
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
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
    private final Map<UUID, Long> waterTimeMap = new HashMap<>();

    private int totalDiamondsBalance = 0;

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "pig_owner");

        if (getCommand("swinia") != null) getCommand("swinia").setExecutor(this);
        if (getCommand("pomoc") != null) getCommand("pomoc").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        loadPluginData();

        new PigTickTask().runTaskTimer(this, 1L, 2L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : pigInventories.keySet()) {
                savePigInventory(uuid);
            }
            savePluginData();
        }, 3600L, 3600L);

        Bukkit.getScheduler().runTaskLater(this, this::loadExistingPigs, 40L);
    }

    @Override
    public void onDisable() {
        for (UUID ownerUuid : pigInventories.keySet()) {
            savePigInventory(ownerUuid);
        }
        savePluginData();

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

        // --- SKARBCOWE I ADMINOWSKIE ---
        if (sub.equals("balance")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Ta komenda jest dostępna tylko dla operatorów (OP)!");
                return true;
            }
            player.sendMessage(ChatColor.GOLD + "[Skarbiec] Zgromadzone diamenty z ulepszeń: " + ChatColor.GREEN + totalDiamondsBalance + " diamentów.");
            return true;
        }

        if (sub.equals("wyplac")) {
            if (!player.getName().equalsIgnoreCase("TaniSzmul")) {
                player.sendMessage(ChatColor.RED + "Tylko gracz TaniSzmul może wypłacić diamenty ze skarbca!");
                return true;
            }

            if (totalDiamondsBalance <= 0) {
                player.sendMessage(ChatColor.RED + "Skarbiec jest pusty!");
                return true;
            }

            int amountToGive = totalDiamondsBalance;
            totalDiamondsBalance = 0;
            savePluginData();

            ItemStack diamonds = new ItemStack(Material.DIAMOND, amountToGive);
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(diamonds);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }

            player.sendMessage(ChatColor.GREEN + "Wypłacono " + amountToGive + " diamentów ze skarbca ulepszeń świnek!");
            return true;
        }

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

        if (sub.equals("podgladopis")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Ta komenda jest dostępna tylko dla operatorów (OP)!");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia podgladopis <nick>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();

            String description = getPigDescription(targetUuid, args[1]);
            player.sendMessage(ChatColor.GOLD + "[OP] Opis świni gracza " + args[1] + ": " + ChatColor.WHITE + description);
            return true;
        }

        if (sub.equals("zmienopis")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Ta komenda jest dostępna tylko dla operatorów (OP)!");
                return true;
            }
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia zmienopis <nick> <nowy_opis>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();

            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            String newDesc = sb.toString().trim();
            savePigDescription(targetUuid, newDesc);

            player.sendMessage(ChatColor.GREEN + "[OP] Zmieniono opis świni gracza " + args[1] + " na: " + ChatColor.WHITE + newDesc);
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
            waterTimeMap.remove(targetUuid);

            File file = new File(getDataFolder() + "/inventories/", targetUuid.toString() + ".yml");
            if (file.exists()) {
                file.delete();
            }

            player.sendMessage(ChatColor.GREEN + "[OP] Zresetowano stan i usunięto dane świni gracza " + args[1]);
            return true;
        }

        // --- KOMENDY GRACZA ---
        if (sub.equals("ulepsz")) {
            if (isPigUpgraded(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + "Twoja świnia posiada już maksymalne ulepszenie (podwójna skrzynia)!");
                return true;
            }

            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem.getType() != Material.DIAMOND || handItem.getAmount() < 10) {
                player.sendMessage(ChatColor.RED + "Nie jest to możliwe, bo nie masz tyle siana! (Wymagane: 10 diamentów w dłoni)");
                return true;
            }

            handItem.setAmount(handItem.getAmount() - 10);
            totalDiamondsBalance += 10;
            savePluginData();

            upgradePigInventory(player.getUniqueId());

            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Gracz " + player.getName() + " ulepszył swoją świnię do podwójnej skrzyni!");
            return true;
        }

        if (sub.equals("opis")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Użycie: /swinia opis <twój_opis>");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                sb.append(args[i]).append(" ");
            }
            String customDesc = sb.toString().trim();
            savePigDescription(player.getUniqueId(), customDesc);
            player.sendMessage(ChatColor.GREEN + "Pomyślnie zmieniono opis Twojej świni!");
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

            despawnPigWithSmoke(player.getUniqueId(), pig, ChatColor.YELLOW + "Świnia została schowana. Przedmioty są bezpieczne!", false, 0);
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

        attachMushroomHat(pig);

        activePigs.put(player.getUniqueId(), pig);
        lastPlayerActivity.put(player.getUniqueId(), System.currentTimeMillis());
        loadPigInventory(player.getUniqueId());
    }

    private void attachMushroomHat(Pig pig) {
        removeMushroomHat(pig);

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
        player.sendMessage(ChatColor.YELLOW + "/swinia spawn " + ChatColor.WHITE + "- Spawnuje/przywołuje świnię do Ciebie.");
        player.sendMessage(ChatColor.YELLOW + "/swinia schowaj " + ChatColor.WHITE + "- Chowa świnię bez utraty przedmiotów.");
        player.sendMessage(ChatColor.YELLOW + "/swinia stoj " + ChatColor.WHITE + "- Zatrzymuje świnię w miejscu.");
        player.sendMessage(ChatColor.YELLOW + "/swinia ulepsz " + ChatColor.WHITE + "- Ulepsza ekwipunek do podwójnej skrzyni (10 diamentów).");
        player.sendMessage(ChatColor.YELLOW + "/swinia nazwa <tekst> " + ChatColor.WHITE + "- Zmienia dopisek w nazwie świni.");
        player.sendMessage(ChatColor.YELLOW + "/swinia opis <tekst> " + ChatColor.WHITE + "- Ustawia własny opis świni.");
        player.sendMessage(ChatColor.YELLOW + "/swinia autorespawn " + ChatColor.WHITE + "- Włącza/wyłącza automatyczny respawn po śmierci.");
        if (player.getName().equalsIgnoreCase("TaniSzmul")) {
            player.sendMessage(ChatColor.AQUA + "[TaniSzmul] /swinia wyplac " + ChatColor.WHITE + "- Wyciąga diamenty ze skarbca ulepszeń.");
        }
        if (player.isOp()) {
            player.sendMessage(ChatColor.RED + "[OP] /swinia balance " + ChatColor.WHITE + "- Sprawdza stan diamentów w skarbcu.");
            player.sendMessage(ChatColor.RED + "[OP] /swinia podglad <nick> " + ChatColor.WHITE + "- Podgląd ekwipunku świni.");
            player.sendMessage(ChatColor.RED + "[OP] /swinia podgladopis <nick> " + ChatColor.WHITE + "- Podgląd opisu świni.");
            player.sendMessage(ChatColor.RED + "[OP] /swinia zmienopis <nick> <opis> " + ChatColor.WHITE + "- Zmiana opisu świni gracza.");
            player.sendMessage(ChatColor.RED + "[OP] /swinia reset <nick> " + ChatColor.WHITE + "- Całkowity reset świni gracza.");
        }
    }

    private void updatePigName(Pig pig, Player owner, String customSuffix) {
        String formattedSuffix = ChatColor.translateAlternateColorCodes('&', customSuffix);
        pig.setCustomName(ChatColor.BOLD + "" + ChatColor.GOLD + "[" + owner.getName() + "] " + ChatColor.RESET + formattedSuffix);
    }

    private void despawnPigWithSmoke(UUID ownerUuid, Pig pig, String reasonMessage, boolean triggerCooldown, long cooldownTimeMs) {
        savePigInventory(ownerUuid);
        Location loc = pig.getLocation().add(0, 0.5, 0);

        pig.getWorld().spawnParticle(Particle.SMOKE_LARGE, loc, 25, 0.3, 0.5, 0.3, 0.05);
        pig.getWorld().spawnParticle(Particle.SMOKE_NORMAL, loc, 20, 0.2, 0.4, 0.2, 0.02);
        pig.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);

        removeMushroomHat(pig);
        pig.remove();
        activePigs.remove(ownerUuid);
        frozenPigs.remove(ownerUuid);
        waterTimeMap.remove(ownerUuid);

        if (triggerCooldown) {
            long time = cooldownTimeMs > 0 ? cooldownTimeMs : 60000L;
            cooldownUntil.put(ownerUuid, System.currentTimeMillis() + time);
        }

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner != null && owner.isOnline() && reasonMessage != null) {
            owner.sendMessage(reasonMessage);
        }
    }

    // --- ZJEDZENIE SCHABU ---
    @EventHandler
    public void onPlayerEat(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material itemType = event.getItem().getType();

        if (itemType == Material.PORKCHOP || itemType == Material.COOKED_PORKCHOP) {
            Pig pig = activePigs.get(player.getUniqueId());
            if (pig != null && pig.isValid()) {
                despawnPigWithSmoke(player.getUniqueId(), pig, ChatColor.DARK_RED + "Jak mogles...", true, 180000L); // 3 minuty = 180 000 ms
            }
        }
    }

    // --- EASTER EGG TOSOWNIK ---
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.getName().equalsIgnoreCase("Tosownik")) {
            if (event.getMessage().trim().equalsIgnoreCase("elo")) {
                Bukkit.getScheduler().runTask(this, () -> {
                    Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Kuba buduje imperium, swinie uciekaja");
                    for (var entry : new HashMap<>(activePigs).entrySet()) {
                        despawnPigWithSmoke(entry.getKey(), entry.getValue(), null, false, 0);
                    }
                });
            }
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
            event.setCancelled(true);

            if (event.getHand() != EquipmentSlot.HAND) return;

            Player player = event.getPlayer();
            String ownerUuidStr = pig.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerUuidStr == null) return;

            UUID ownerUuid = UUID.fromString(ownerUuidStr);

            if (!player.getUniqueId().equals(ownerUuid) && !player.isOp()) {
                Player ownerPlayer = Bukkit.getPlayer(ownerUuid);
                String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "innego gracza";
                String desc = getPigDescription(ownerUuid, ownerName);

                player.sendMessage(ChatColor.RED + "Nie możesz okraść tej świnki! Należy ona do gracza " + ChatColor.YELLOW + ownerName + ChatColor.RED + ".");
                player.sendMessage(ChatColor.GOLD + "Opis świni: " + ChatColor.ITALIC + ChatColor.WHITE + desc);
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

    public boolean isPigUpgraded(UUID ownerUuid) {
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            return config.getBoolean("upgraded", false);
        }
        return false;
    }

    public void upgradePigInventory(UUID ownerUuid) {
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("upgraded", true);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Inventory oldInv = pigInventories.get(ownerUuid);
        Inventory newInv = Bukkit.createInventory(null, 54, ChatColor.DARK_GRAY + "Ekwipunek Świnki (Ulepszony)");

        if (oldInv != null) {
            for (int i = 0; i < oldInv.getSize(); i++) {
                newInv.setItem(i, oldInv.getItem(i));
            }
        }

        pigInventories.put(ownerUuid, newInv);
        savePigInventory(ownerUuid);
    }

    public void savePigInventory(UUID ownerUuid) {
        Inventory inv = pigInventories.get(ownerUuid);
        if (inv == null) return;

        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        config.set("upgraded", inv.getSize() > 27);

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
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        boolean isUpgraded = false;

        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            isUpgraded = config.getBoolean("upgraded", false);
        }

        int size = isUpgraded ? 54 : 27;
        Inventory inv = Bukkit.createInventory(null, size, ChatColor.DARK_GRAY + (isUpgraded ? "Ekwipunek Świnki (Ulepszony)" : "Ekwipunek Świnki"));

        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            for (int i = 0; i < size; i++) {
                if (config.contains("slot." + i)) {
                    inv.setItem(i, config.getItemStack("slot." + i));
                }
            }
        }
        pigInventories.put(ownerUuid, inv);
    }

    private String getPigDescription(UUID ownerUuid, String fallbackName) {
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (config.contains("description")) {
                return config.getString("description");
            }
        }
        return "Świnia gracza " + fallbackName;
    }

    private void savePigDescription(UUID ownerUuid, String description) {
        File file = new File(getDataFolder() + "/inventories/", ownerUuid.toString() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("description", description);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void savePluginData() {
        File file = new File(getDataFolder(), "data.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("diamonds_balance", totalDiamondsBalance);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPluginData() {
        File file = new File(getDataFolder(), "data.yml");
        if (file.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            totalDiamondsBalance = config.getInt("diamonds_balance", 0);
        }
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
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Świnia znika, ponieważ nie jesteś w trybie SURVIVAL!", false, 0);
                    continue;
                }

                if (owner.getHealth() <= 10.0) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Jest zbyt niebezpiecznie (masz 5 serduszek lub mniej)! Twoja świnia uciekła. Musisz odczekać 1 minutę przed ponownym przyzwaniem.", true, 60000L);
                    continue;
                }

                long lastAct = lastPlayerActivity.getOrDefault(ownerUuid, now);
                if (now - lastAct > 600000L) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.YELLOW + "Byłeś/aś AFK przez ponad 10 minut! Twoja świnia schowała się bezpiecznie.", false, 0);
                    continue;
                }

                if (pig.getLocation().getBlock().getType() == Material.LAVA) {
                    despawnPigWithSmoke(ownerUuid, pig, ChatColor.RED + "Twoja świnia wpadła do lawy i uciekła! Musisz odczekać 1 minutę przed ponownym przyzwaniem.", true, 60000L);
                    continue;
                }

                if (owner.getLocation().getBlock().getType() == Material.WATER) {
                    long waterStart = waterTimeMap.getOrDefault(ownerUuid, now);
                    waterTimeMap.putIfAbsent(ownerUuid, waterStart);

                    if (now - waterStart >= 180000L) {
                        despawnPigWithSmoke(ownerUuid, pig, ChatColor.BLUE + "Spędziłeś/aś ponad 3 minuty w wodzie! Twoja świnia uciekła przed zamoknięciem. Odczekaj 1 minutę.", true, 60000L);
                        continue;
                    }
                } else {
                    waterTimeMap.remove(ownerUuid);
                }

                if (pig.getPassengers().isEmpty()) {
                    attachMushroomHat(pig);
                }

                Location pigLoc = pig.getLocation();
                Location ownerLoc = owner.getLocation();

                boolean isDifferentWorld = !pigLoc.getWorld().equals(ownerLoc.getWorld());
                boolean isTooFar = !isDifferentWorld && pigLoc.distance(ownerLoc) > 20.0;

                if (!frozenPigs.containsKey(ownerUuid)) {
                    if (isDifferentWorld || isTooFar) {
                        pig.teleport(ownerLoc);
                        continue;
                    }
                } else {
                    Location anchorLoc = frozenPigs.get(ownerUuid);
                    if (pigLoc.getWorld().equals(anchorLoc.getWorld()) && pigLoc.distance(anchorLoc) > 3.0) {
                        Vector backToAnchor = anchorLoc.toVector().subtract(pigLoc.toVector()).normalize().multiply(0.2);
                        pigLoc.add(backToAnchor);
                        pig.teleport(pigLoc);
                    }
                    continue;
                }

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
