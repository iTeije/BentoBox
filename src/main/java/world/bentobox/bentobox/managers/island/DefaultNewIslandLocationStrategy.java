package world.bentobox.bentobox.managers.island;

import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.util.Util;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default strategy for generating locations for island
 *
 * @author tastybento, leonardochaia
 * @since 1.8.0
 */
public class DefaultNewIslandLocationStrategy implements NewIslandLocationStrategy {

    /**
     * The amount times to tolerate island check returning blocks without kwnon
     * island.
     */
    protected static final Integer MAX_UNOWNED_ISLANDS = 20;

    protected enum Result {
        ISLAND_FOUND, BLOCKS_IN_AREA, FREE
    }

    protected BentoBox plugin = BentoBox.getInstance();

    @Override
    public Location getNextLocation(World world) {
        // Note:
        // The only sync load I found was the isIsland method call

        Location last = plugin.getIslands().getLast(world);

        if (last == null) {
            last = new Location(world,
                    (double) plugin.getIWM().getIslandXOffset(world) + plugin.getIWM().getIslandStartX(world),
                    plugin.getIWM().getIslandHeight(world),
                    (double) plugin.getIWM().getIslandZOffset(world) + plugin.getIWM().getIslandStartZ(world));
        }

        // Find a free spot
        Map<Result, Integer> result = new EnumMap<>(Result.class);
        // Check center
        Result r = isIsland(last);

        while (!r.equals(Result.FREE) && result.getOrDefault(Result.BLOCKS_IN_AREA, 0) < MAX_UNOWNED_ISLANDS) {
            last = nextGridLocation(last);
            result.put(r, result.getOrDefault(r, 0) + 1);

            // The method call below caused an error
            r = isIsland(last);
        }


        if (!r.equals(Result.FREE)) {
            // We could not find a free spot within the limit required. It's likely this
            // world is not empty
            plugin.logError("Could not find a free spot for islands! Is this world empty?");
            plugin.logError("Blocks around center locations: " + result.getOrDefault(Result.BLOCKS_IN_AREA, 0) + " max "
                    + MAX_UNOWNED_ISLANDS);
            plugin.logError("Known islands: " + result.getOrDefault(Result.ISLAND_FOUND, 0) + " max unlimited.");

            return null;
        }

        plugin.getIslands().setLast(last);
        return last;
    }

    /*** Checks if there is an island or blocks at this location
     *
     * @param location - the location
     * @return Result enum if island found, null if blocks found, false if nothing found
     */
    protected Result isIsland(Location location) {
        // Quick check
        if (plugin.getIslands().getIslandAt(location).isPresent()) return Result.ISLAND_FOUND;

        World world = location.getWorld();

        // Check 4 corners
        int dist = plugin.getIWM().getIslandDistance(world);

        Set<Location> locations = new HashSet<>();

        locations.add(location);
        locations.add(new Location(world, location.getX() - dist, 0, location.getZ() - dist));
        locations.add(new Location(world, location.getX() - dist, 0, location.getZ() + dist - 1));
        locations.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() - dist));
        locations.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() + dist - 1));

        boolean generated = false;
        for (Location loc : locations) {
            if (plugin.getIslands().getIslandAt(loc).isPresent() || plugin.getIslandDeletionManager().inDeletion(loc)) {
                return Result.ISLAND_FOUND;
            }

            if (Util.isChunkGenerated(loc)) {
                generated = true;
            }
        }

        // If chunk has not been generated yet, then it's not occupied
        if (!generated) {
            return Result.FREE;
        }

        // Block check
        if (!plugin.getIWM().isUseOwnGenerator(world)) {
            // The old method call (Location#getBlock) caused the chunk to synchronously load

            ExecutorService pool = Executors.newCachedThreadPool();

            // Load chunk

            AtomicReference<Result> result = new AtomicReference<>();

            return asyncRequest(location);

//            return CompletableFuture.supplyAsync(location::getChunk, pool)
//                    .exceptionally(throwable -> null)
//                    .thenAccept(chunk -> {
//                        Block block = chunk.getBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
//
//                        if (Arrays.stream(BlockFace.values()).anyMatch(blockFace ->
//                                !block.getRelative(blockFace).isEmpty() && !block.getRelative(blockFace).getType().equals(Material.WATER))) {
//
//                            // Block found
//                            plugin.getIslands().createIsland(location);
//                            result.set(Result.BLOCKS_IN_AREA);
//                        }
//                        result.set(Result.FREE);
//
//                    })
//                    .thenApplyAsync(aVoid -> result.get()).join();

//            try {
//
//                Chunk chunk = cChunk.get();
//
//                Block block = chunk.getBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
//
//                if (Arrays.stream(BlockFace.values()).anyMatch(blockFace ->
//                        !block.getRelative(blockFace).isEmpty() && !block.getRelative(blockFace).getType().equals(Material.WATER))) {
//
//                    // Block found
//                    plugin.getIslands().createIsland(location);
//                    return Result.BLOCKS_IN_AREA;
//                }
//                return Result.FREE;
//
//            } catch (InterruptedException | ExecutionException exception) {
//                plugin.logStacktrace(exception.getCause());
//            }


        }

        return Result.FREE;
    }

    private Result asyncRequest(Location location) {
        // Not shortened due to readability
        Chunk chunk = CompletableFuture.supplyAsync(() -> {
            return location.getChunk();
        }).thenApplyAsync(loadedChunk -> {
            return loadedChunk;
        }).join();

        Block block = chunk.getBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        if (Arrays.stream(BlockFace.values()).anyMatch(blockFace ->
                !block.getRelative(blockFace).isEmpty() && !block.getRelative(blockFace).getType().equals(Material.WATER))) {

            // Block found
            plugin.getIslands().createIsland(location);
            return Result.BLOCKS_IN_AREA;
        }
        return Result.FREE;
    }

    private final HashMap<Location, Chunk> chunkCache = new HashMap<>();

    public void cacheChunk(Location location, Chunk chunk) {
        chunkCache.put(location, chunk);
    }

    public Chunk getChunk(Location location) {
        return chunkCache.getOrDefault(location, null);
    }

    public void removeCachedChunk(Location location) {
        chunkCache.remove(location);
    }

    /**
     * Finds the next free island spot based off the last known island Uses
     * island_distance setting from the config file Builds up in a grid fashion
     *
     * @param lastIsland - last island location
     * @return Location of next free island
     */
    private Location nextGridLocation(final Location lastIsland) {
        int x = lastIsland.getBlockX();
        int z = lastIsland.getBlockZ();
        int d = plugin.getIWM().getIslandDistance(lastIsland.getWorld()) * 2;
        if (x < z) {
            if (-1 * x < z) {
                lastIsland.setX(lastIsland.getX() + d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        if (x > z) {
            if (-1 * x >= z) {
                lastIsland.setX(lastIsland.getX() - d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() - d);
            return lastIsland;
        }
        if (x <= 0) {
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        lastIsland.setZ(lastIsland.getZ() - d);
        return lastIsland;
    }
}
