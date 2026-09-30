package ru.atmosphere;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class Atmosphere extends JavaPlugin implements Listener {

    private final Random random = new Random();
    private final Set<UUID> warmed = new HashSet<>();
    private BukkitTask campfireTask;
    private BukkitTask ambientTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startTasks();
    }

    @Override
    public void onDisable() {
        stopTasks();
    }

    private void startTasks() {
        stopTasks();

        if (getConfig().getBoolean("campfire.enabled", true)) {
            campfireTask = getServer().getScheduler()
                    .runTaskTimer(this, this::tickCampfires, 40L, 40L);
        }
        if (getConfig().getBoolean("ambient.enabled", true)) {
            long period = Math.max(1, getConfig().getLong("ambient.interval-seconds", 15)) * 20L;
            ambientTask = getServer().getScheduler()
                    .runTaskTimer(this, this::tickAmbient, period, period);
        }
    }

    private void stopTasks() {
        if (campfireTask != null) campfireTask.cancel();
        if (ambientTask != null) ambientTask.cancel();
        campfireTask = null;
        ambientTask = null;
        warmed.clear();
    }

    // ---------- Тепло костра ----------

    private void tickCampfires() {
        int radius = Math.max(1, getConfig().getInt("campfire.radius", 3));
        String msg = getConfig().getString("campfire.message", "");

        for (Player player : getServer().getOnlinePlayers()) {
            if (player.isDead()) continue;

            Block found = findLitCampfire(player.getLocation(), radius);
            if (found != null) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.REGENERATION, 60, 0, true, false, true));

                Location p = found.getLocation().add(0.5, 1.2, 0.5);
                player.spawnParticle(Particle.HEART, player.getLocation().add(0, 2.1, 0),
                        1, 0.2, 0.1, 0.2, 0);
                player.spawnParticle(Particle.SMALL_FLAME, p, 3, 0.2, 0.1, 0.2, 0.005);

                if (warmed.add(player.getUniqueId()) && !msg.isEmpty()) {
                    player.sendActionBar(Component.text(msg, NamedTextColor.GOLD));
                }
            } else {
                warmed.remove(player.getUniqueId());
            }
        }
    }

    private Block findLitCampfire(Location center, int r) {
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    if (Tag.CAMPFIRES.isTagged(b.getType())
                            && b.getBlockData() instanceof Campfire c && c.isLit()) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        warmed.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Звуки окружения ----------

    private void tickAmbient() {
        int chance = getConfig().getInt("ambient.chance-percent", 20);
        boolean caves = getConfig().getBoolean("ambient.cave-sounds", true);
        boolean howls = getConfig().getBoolean("ambient.night-howls", true);

        for (Player player : getServer().getOnlinePlayers()) {
            if (random.nextInt(100) >= chance) continue;

            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world.getEnvironment() != World.Environment.NORMAL) continue;

            boolean underground = loc.getY() < 45
                    && loc.getBlock().getLightFromSky() == 0;

            if (underground) {
                if (caves) {
                    player.playSound(loc, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT,
                            0.7f, 0.8f + random.nextFloat() * 0.4f);
                }
                continue;
            }

            long time = world.getTime();
            boolean night = time >= 13000 && time <= 23000;
            if (!howls || !night || world.hasStorm()) continue;

            String biome = loc.getBlock().getBiome().getKey().getKey();
            if (!(biome.contains("forest") || biome.contains("taiga") || biome.contains("grove"))) continue;

            Sound howl = Registry.SOUNDS.get(NamespacedKey.minecraft("entity.wolf.howl"));
            if (howl == null) continue;

            // звук «где-то вдалеке»
            double angle = random.nextDouble() * Math.PI * 2;
            Location far = loc.clone().add(Math.cos(angle) * 28, 0, Math.sin(angle) * 28);
            player.playSound(far, howl, SoundCategory.AMBIENT,
                    0.5f, 0.9f + random.nextFloat() * 0.2f);
        }
    }

    // ---------- Эхо смерти ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("death.enabled", true)) return;

        Player p = e.getEntity();
        Location loc = p.getLocation();
        World w = loc.getWorld();

        w.spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.03);
        w.playSound(loc, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.PLAYERS, 1.2f, 0.7f);

        if (getConfig().getBoolean("death.send-coords", true)) {
            String text = getConfig().getString("death.message", "")
                    .replace("%x%", String.valueOf(loc.getBlockX()))
                    .replace("%y%", String.valueOf(loc.getBlockY()))
                    .replace("%z%", String.valueOf(loc.getBlockZ()))
                    .replace("%world%", w.getName());
            p.sendMessage(Component.text(text, NamedTextColor.GRAY));
        }
    }

    // ---------- Команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            startTasks();
            sender.sendMessage(Component.text("Конфиг VanillaAtmosphere перезагружен.", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("Использование: /atmosphere reload", NamedTextColor.YELLOW));
        return true;
    }
}
