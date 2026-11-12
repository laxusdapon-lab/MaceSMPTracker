package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;

/**
 * Looks at every Mace that is currently reachable through the API: online players (inventory, cursor,
 * ender chest), every tile entity in loaded chunks, item entities, item frames, mob equipment and
 * entity inventories. The scan is spread over several ticks (chunks-per-tick); the final resolution
 * step runs in a single tick, so two copies of the same id that are BOTH still present at that moment
 * are a genuine duplicate and the extra copies are removed.
 */
final class Sweeper {
    static final class Result {
        final Map<UUID, List<Sighting>> byId = new LinkedHashMap<>();
        final List<Sighting> untagged = new ArrayList<>();
        final Set<UUID> seenIds = new HashSet<>();
        int players;
        int chunks;
        int duplicatesRemoved;
        int adopted;
        int untaggedRemoved;
        int untaggedIgnored;
        int reactivated;
        int destroyedRemoved;
    }

    private static final EquipmentSlot[] MOB_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final TrackerService svc;
    private final MaceItems items;
    private boolean running;

    Sweeper(TrackerService svc) {
        this.svc = svc;
        this.items = svc.items;
    }

    boolean isRunning() {
        return running;
    }

    /** Starts a sweep; returns false if one is already running. The callback runs on the main thread. */
    boolean start(Collection<? extends Player> players, boolean withChunks, Consumer<Result> done) {
        if (running) return false;
        running = true;
        new Job(new ArrayList<>(players), withChunks, done).begin();
        return true;
    }

    private final class Job implements Runnable {
        private final List<Player> players;
        private final Consumer<Result> done;
        private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        private final Result result = new Result();
        private final Set<String> seenKeys = new HashSet<>();
        private BukkitTask task;
        private boolean playersDone;

        Job(List<Player> players, boolean withChunks, Consumer<Result> done) {
            this.players = players;
            this.done = done;
            if (withChunks) {
                for (World w : Bukkit.getWorlds()) chunks.addAll(Arrays.asList(w.getLoadedChunks()));
            }
        }

        void begin() {
            task = Bukkit.getScheduler().runTaskTimer(svc.plugin, this, 1L, 1L);
        }

        @Override
        public void run() {
            try {
                if (!playersDone) {
                    for (Player p : players) if (p.isOnline()) scanPlayer(p);
                    playersDone = true;
                }
                int budget = svc.settings.chunksPerTick;
                while (budget-- > 0 && !chunks.isEmpty()) scanChunk(chunks.poll());
                if (chunks.isEmpty()) {
                    task.cancel();
                    resolve(result);
                    running = false;
                    done.accept(result);
                }
            } catch (Throwable t) {
                task.cancel();
                running = false;
                svc.log.log(java.util.logging.Level.SEVERE, "Mace sweep failed: " + t, t);
            }
        }

        // ------------------------------------------------------------ scanning

        private void scanPlayer(Player p) {
            Settings s = svc.settings;
            result.players++;
            String uuid = p.getUniqueId().toString();
            if (s.trackPlayerInventories) {
                scanInventory(p.getInventory(), "p:" + uuid, "player:" + p.getName(), 0);
                ItemStack cursor = p.getItemOnCursor();
                if (cursor != null) scanStack(new Refs.CursorRef(p), cursor, "player:" + p.getName(), 0);
            }
            if (s.trackEnderChests) {
                scanInventory(p.getEnderChest(), "ec:" + uuid, "enderchest:" + p.getName(), 1);
            }
        }

        private void scanChunk(Chunk chunk) {
            if (!chunk.isLoaded()) return;
            Settings s = svc.settings;
            result.chunks++;
            if (s.trackContainers) {
                for (BlockState st : chunk.getTileEntities()) {
                    Inventory inv;
                    if (st instanceof Chest ch) inv = ch.getBlockInventory(); // one half of a double chest
                    else if (st instanceof Container c) inv = c.getInventory();
                    else continue;
                    Location l = st.getLocation();
                    String pos = l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
                    scanInventory(inv, "b:" + pos, "container:" + pos, 2);
                }
            }
            for (Entity e : chunk.getEntities()) {
                if (e instanceof Player) continue;
                if (e instanceof Item it) {
                    if (s.trackDroppedItems) scanStack(new Refs.ItemEntityRef(it), it.getItemStack(), "dropped:" + pos(e), 4);
                    continue;
                }
                if (!s.trackEntities) continue;
                if (e instanceof ItemFrame f) {
                    scanStack(new Refs.FrameRef(f), f.getItem(), "frame:" + pos(e), 3);
                }
                if (e instanceof LivingEntity le) {
                    EntityEquipment eq = le.getEquipment();
                    if (eq != null) {
                        for (EquipmentSlot slot : MOB_SLOTS) {
                            scanStack(new Refs.EquipRef(le, slot), eq.getItem(slot), "mob:" + e.getType().name() + " " + pos(e), 3);
                        }
                    }
                }
                if (e instanceof InventoryHolder h && s.trackContainers) {
                    scanInventory(h.getInventory(), "ei:" + e.getUniqueId(), "entity:" + e.getType().name() + " " + pos(e), 3);
                }
            }
        }

        private String pos(Entity e) {
            Location l = e.getLocation();
            return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        }

        private void scanInventory(Inventory inv, String keyPrefix, String where, int priority) {
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack st = contents[i];
                if (st == null) continue;
                Material t = st.getType();
                if (t == Material.MACE || isShulker(t)) {
                    scanStack(new Refs.InvRef(inv, i, keyPrefix + ":" + i), st, where, priority);
                }
            }
        }

        private void scanStack(Ref ref, ItemStack st, String where, int priority) {
            if (st == null) return;
            Material t = st.getType();
            if (t == Material.MACE) {
                record(ref, st, where, priority);
            } else if (isShulker(t)) {
                ItemMeta meta = st.getItemMeta();
                if (!(meta instanceof BlockStateMeta bsm)) return;
                BlockState state = bsm.getBlockState();
                if (!(state instanceof ShulkerBox sb)) return;
                ItemStack[] inner = sb.getInventory().getContents();
                for (int i = 0; i < inner.length; i++) {
                    if (inner[i] != null && inner[i].getType() == Material.MACE) {
                        record(new Refs.ShulkerRef(ref, i), inner[i], where + ">shulker", priority);
                    }
                }
            }
        }

        private void record(Ref ref, ItemStack st, String where, int priority) {
            if (!seenKeys.add(ref.key())) return; // the same slot reached twice
            UUID id = items.getId(st);
            Sighting sg = new Sighting(ref, id, where, priority);
            if (id == null) result.untagged.add(sg);
            else result.byId.computeIfAbsent(id, k -> new ArrayList<>()).add(sg);
        }
    }

    private static boolean isShulker(Material m) {
        return m.name().endsWith("SHULKER_BOX");
    }

    // ---------------------------------------------------------------- resolution (single tick)

    private void resolve(Result r) {
        Settings s = svc.settings;
        MaceRegistry reg = svc.registry;
        long now = System.currentTimeMillis();
        boolean changed = false;

        // 1) Duplicates: the same id present in two different slots right now.
        for (Map.Entry<UUID, List<Sighting>> en : r.byId.entrySet()) {
            UUID id = en.getKey();
            List<Sighting> valid = new ArrayList<>();
            for (Sighting sg : en.getValue()) {
                if (id.equals(items.getId(sg.ref.get()))) valid.add(sg);
            }
            valid.sort(Comparator.comparingInt(x -> x.priority));
            for (int i = 1; i < valid.size(); i++) {
                valid.get(i).ref.set(null);
                r.duplicatesRemoved++;
                svc.warn("Duplicate Mace " + TrackerService.shortId(id) + " removed from " + valid.get(i).where
                        + " (kept the copy in " + valid.get(0).where + ")");
            }
            en.setValue(valid.isEmpty() ? valid : new ArrayList<>(valid.subList(0, 1)));
        }

        // 2) Compare surviving copies with the registry.
        for (Map.Entry<UUID, List<Sighting>> en : r.byId.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            UUID id = en.getKey();
            Sighting sg = en.getValue().get(0);
            r.seenIds.add(id);
            MaceRecord rec = reg.get(id);
            if (rec == null) {
                changed |= handleUntracked(r, s, sg, id, now);
            } else if (rec.state == MaceRecord.State.DESTROYED) {
                if (s.zombie == Settings.ZombiePolicy.REACTIVATE) {
                    if (reg.reactivate(id, now, sg.where)) {
                        r.reactivated++;
                        changed = true;
                        svc.warn("Mace " + TrackerService.shortId(id) + " was marked destroyed but exists in " + sg.where + "; counted as active again.");
                    }
                } else {
                    sg.ref.set(null);
                    r.destroyedRemoved++;
                    svc.warn("Removed Mace " + TrackerService.shortId(id) + " from " + sg.where + " (it was already marked destroyed).");
                }
            } else {
                reg.touch(id, sg.where, now);
            }
        }

        // 3) Maces that carry no id at all.
        for (Sighting sg : r.untagged) {
            ItemStack cur = sg.ref.get();
            if (cur == null || cur.getType() != Material.MACE || items.getId(cur) != null) continue;
            changed |= handleUntracked(r, s, sg, null, now);
        }

        if (changed) svc.changed();
        svc.lastSweep = new Date() + " - " + r.players + " player(s), " + r.chunks + " chunk(s), "
                + r.duplicatesRemoved + " duplicate(s) removed";
    }

    private boolean handleUntracked(Result r, Settings s, Sighting sg, UUID knownId, long now) {
        switch (s.untracked) {
            case REGISTER: {
                ItemStack cur = sg.ref.get();
                if (cur == null) return false;
                UUID id = knownId != null ? knownId : UUID.randomUUID();
                if (knownId == null) sg.ref.set(items.withId(cur, id));
                if (svc.registry.register(id, "adopted", "adopted", now, sg.where)) {
                    r.adopted++;
                    r.seenIds.add(id);
                    svc.warn("Adopted a Mace that was not forged through the tracker (" + sg.where + "), id " + TrackerService.shortId(id)
                            + ". It now counts toward the limit.");
                    return true;
                }
                return false;
            }
            case REMOVE:
                sg.ref.set(null);
                r.untaggedRemoved++;
                svc.warn("Removed an untracked Mace from " + sg.where + " (untracked-mace-policy: REMOVE).");
                return false;
            default:
                r.untaggedIgnored++;
                return false;
        }
    }
}
