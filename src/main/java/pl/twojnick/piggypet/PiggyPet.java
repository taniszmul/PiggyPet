package com.example.swiniaplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class SwiniaPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // Mapowanie: Właściciel -> Jego Świnia
    private final Map<UUID, Pig> playerPigs = new HashMap<>();

    // Mapowanie: Świnia -> Cel Stalkowania (Target Player)
    private final Map<Pig, Player> pigTargets = new HashMap<>();

    // Mapowanie: Właściciel -> Zadanie timera bez świni (1 godzina)
    private final Map<UUID, BukkitTask> noPigTimers = new HashMap<>();

    // Tablica 5 spersonalizowanych komunikatów
    private final String[] missMessages = new String[]{
            "&d[Świnia] &fCześć %player%! Twoja świnka bardzo za Tobą tęskni... Przywołaj ją!",
            "&d[Świnia] &f%player%, minęła godzina, a nikt mnie nie pogłaskał... Potrzebuję Cię!",
            "&d[Świnia] &fGdzie jesteś %player%? Twoja ulubiona świnia czeka w chlewikowa i chce do Ciebie dołączyć!",
            "&d[Świnia] &f%player%! Chrum chrum! Słyszysz to? To Twoja świnia płacze w samotności...",
            "&d[Świnia] &fSamotny spacer po świecie? %player%, przywołaj swoją świnię komendą /swinia!"
    };

    private final Random random = new Random();

    @Override
    public void onEnable() {
        this.getCommand("swinia").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        // Pętla AI dla śledzenia graczy przez świnie (co 5 ticków = 0.25s)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickPigAI();
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    @Override
    public void onDisable() {
        // Usunięcie wszystkich świń przy wyłączeniu
        for (Pig pig : playerPigs.values()) {
            if (pig != null && pig.isValid()) {
                pig.remove();
            }
        }
        playerPigs.clear();
        pigTargets.clear();
    }

    // --- ZARZĄDZANIE TIMEREM 1 GODZINY BEZ ŚWINI ---

    private void startNoPigTimer(Player player) {
        cancelNoPigTimer(player);

        // 60 minut * 60 sekund * 20 ticków = 72000 ticków
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && !playerPigs.containsKey(player.getUniqueId())) {
                    String rawMsg = missMessages[random.nextInt(missMessages.length)];
                    String formattedMsg = ChatColor.translateAlternateColorCodes('&', rawMsg.replace("%player%", player.getName()));
                    player.sendMessage(formattedMsg);
                    player.playSound(player.getLocation(), Sound.ENTITY_PIG_AMBIENT, 1.0f, 1.2f);
                }
            }
        }.runTaskLater(this, 72000L); // 1 godzina

        noPigTimers.put(player.getUniqueId(), task);
    }

    private void cancelNoPigTimer(Player player) {
        if (noPigTimers.containsKey(player.getUniqueId())) {
            noPigTimers.get(player.getUniqueId()).cancel();
            noPigTimers.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        startNoPigTimer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player owner = event.getPlayer();
        cancelNoPigTimer(owner);

        // Usunięcie świni przy wyjściu właściciela
        if (playerPigs.containsKey(owner.getUniqueId())) {
            Pig pig = playerPigs.remove(owner.getUniqueId());
            if (pig != null && pig.isValid()) {
                pigTargets.remove(pig);
                pig.remove();
            }
        }
    }

    // --- LOGIKA OBSŁUGI KOMEND ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Komenda tylko dla graczy!");
            return true;
        }

        Player player = (Player) sender;

        // /swinia follow <nick>
        if (args.length >= 2 && args[0].equalsIgnoreCase("follow")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "Nie znaleziono gracza o nicku " + args[1]);
                return true;
            }

            if (target.equals(player)) {
                player.sendMessage(ChatColor.YELLOW + "Nie możesz nakazać świni stalkować samego siebie!");
                return true;
            }

            Pig pig = getOrCreatePig(player);
            pig.teleport(target.getLocation());
            pigTargets.put(pig, target);

            // Dodatkowo: flaga czy świnia jest w trybie natarczywym (pierwsze 60s)
            pig.setMetadata("stalking_start", new org.bukkit.metadata.FixedMetadataValue(this, System.currentTimeMillis()));

            player.sendMessage(ChatColor.GREEN + "Twoja świnia poszła stalkować gracza " + target.getName() + "!");
            target.sendMessage(ChatColor.LIGHT_PURPLE + "Czujesz na sobie czyjś wzrok... Świnia gracza " + player.getName() + " zaczęła Cię śledzić!");
            return true;
        }

        // /swinia (działa jak zwykłe przywołanie / cofnięcie z follow)
        cancelNoPigTimer(player);
        Pig pig = getOrCreatePig(player);
        
        // Jeśli świnia kogoś kogoś śledziła, zerwij śledzenie i wróć do gracza
        pigTargets.remove(pig);
        pig.teleport(player.getLocation());
        player.sendMessage(ChatColor.GREEN + "Twoja świnia powróciła do Ciebie!");

        return true;
    }

    private Pig getOrCreatePig(Player owner) {
        Pig pig = playerPigs.get(owner.getUniqueId());
        if (pig == null || !pig.isValid()) {
            pig = (Pig) owner.getWorld().spawnEntity(owner.getLocation(), EntityType.PIG);
            pig.setCustomName(ChatColor.LIGHT_PURPLE + "Świnia gracza " + owner.getName());
            pig.setCustomNameVisible(true);
            playerPigs.put(owner.getUniqueId(), pig);
        }
        return pig;
    }

    // --- AI ŚLEDZENIA I STALKOWANIA ---

    private void tickPigAI() {
        for (Map.Entry<UUID, Pig> entry : playerPigs.entrySet()) {
            Pig pig = entry.getValue();
            if (pig == null || !pig.isValid()) continue;

            Player owner = Bukkit.getPlayer(entry.getKey());
            Player target = pigTargets.get(pig);

            // Jeśli ma cel follow (stalkowanie)
            if (target != null && target.isOnline()) {
                long startTime = pig.hasMetadata("stalking_start") ? pig.getMetadata("stalking_start").get(0).asLong() : 0;
                boolean isAggressivePhase = (System.currentTimeMillis() - startTime) < 60000; // Pierwsza 1 minuta

                Location targetLoc = target.getLocation();
                Location pigLoc = pig.getLocation();

                if (!pigLoc.getWorld().equals(targetLoc.getWorld()) || pigLoc.distanceSquared(targetLoc) > 400) {
                    pig.teleport(targetLoc); // Teleport przy zbyt dużej odległości
                } else {
                    // W pierwszej minucie świnia próbuje wchodzić w gracza (dystans 0.2m), potem normalnie podąża (1.5m)
                    double stopDistance = isAggressivePhase ? 0.2 : 1.8;
                    if (pigLoc.distance(targetLoc) > stopDistance) {
                        pig.getPathfinder().moveTo(targetLoc, isAggressivePhase ? 1.45 : 1.25);
                    }
                }
            } else if (owner != null && owner.isOnline()) {
                // Domyślne chodzenie za właścicielem
                Location ownerLoc = owner.getLocation();
                Location pigLoc = pig.getLocation();
                if (!pigLoc.getWorld().equals(ownerLoc.getWorld()) || pigLoc.distanceSquared(ownerLoc) > 256) {
                    pig.teleport(ownerLoc);
                } else if (pigLoc.distance(ownerLoc) > 2.0) {
                    pig.getPathfinder().moveTo(ownerLoc, 1.2);
                }
            }
        }
    }

    // --- EVENT NAKARMIENIA MARCHEWKĄ ---

    @EventHandler
    public void onPigInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Pig)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Pig pig = (Pig) event.getRightClicked();
        Player clicker = event.getPlayer();

        // Sprawdzamy czy ta świnia kogoś obecnie followuje
        if (!pigTargets.containsKey(pig)) return;

        // Sprawdzenie czy gracz trzyma marchewkę
        Material itemInHand = clicker.getInventory().getItemInMainHand().getType();
        if (itemInHand == Material.CARROT || itemInHand == Material.CARROT_ON_A_STICK) {
            
            event.setCancelled(true); // Anulujemy domyślną interakcję

            // Szukamy właściciela tej świni
            UUID ownerUUID = null;
            for (Map.Entry<UUID, Pig> entry : playerPigs.entrySet()) {
                if (entry.getValue().equals(pig)) {
                    ownerUUID = entry.getKey();
                    break;
                }
            }

            Player owner = (ownerUUID != null) ? Bukkit.getPlayer(ownerUUID) : null;

            if (owner != null && owner.isOnline()) {
                // Właściciel JEST na serwerze -> Świnia wraca do właściciela
                pigTargets.remove(pig);
                pig.teleport(owner.getLocation());

                clicker.sendMessage(ChatColor.GREEN + "Dałeś marchewkę świni! Odstraszyłeś ją i wróciła do swojego właściciela.");
                owner.sendMessage(ChatColor.YELLOW + "Twoja świnia została przekupiona marchewką przez " + clicker.getName() + " i do Ciebie wróciła!");
                pig.getWorld().playSound(pig.getLocation(), Sound.ENTITY_PIG_EAT, 1.0f, 1.0f);

            } else {
                // Właściciela NIE MA na serwerze -> Świnia znika z dymem
                pigTargets.remove(pig);
                if (ownerUUID != null) playerPigs.remove(ownerUUID);

                Location loc = pig.getLocation();
                pig.getWorld().spawnParticle(Particle.SMOKE, loc.add(0, 0.5, 0), 30, 0.3, 0.3, 0.3, 0.05);
                pig.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                pig.getWorld().playSound(loc, Sound.ENTITY_PIG_DEATH, 0.5f, 1.2f);
                
                pig.remove();

                clicker.sendMessage(ChatColor.GOLD + "Dałeś marchewkę świni bez właściciela. Świnia rozpłynęła się w dymie!");
            }
        }
    }
}
