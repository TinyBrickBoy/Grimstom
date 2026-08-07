package ac.grim.grimac.platform.minestom.world;

import ac.grim.grimac.platform.api.world.PlatformChunk;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Grim {@link PlatformWorld} over a Minestom {@link Instance}.
 * <p>
 * TODO Phase 3: block reads feed Grim's world replica for collision/prediction; verify that
 * Minestom {@code Block#stateId()} matches PacketEvents' global block-state id for 26.2 across
 * all blocks (spot-checked identical for the common cases).
 */
public final class MinestomPlatformWorld implements PlatformWorld {

    private final Instance instance;

    public MinestomPlatformWorld(Instance instance) {
        this.instance = instance;
    }

    public Instance getInstance() {
        return instance;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return instance != null && instance.getChunk(chunkX, chunkZ) != null;
    }

    @Override
    public WrappedBlockState getBlockAt(int x, int y, int z) {
        // Grims Checks laufen auf dem Paket-Feeder-Thread, wo der Player transient instance==null zeigt.
        // Ohne Instance kennen wir den Block nicht -> Luft zurückgeben (statt NPE), bis der nächste Tick
        // wieder mit gültiger Instance liest.
        if (instance == null) return WrappedBlockState.getByGlobalId(0);
        return WrappedBlockState.getByGlobalId(instance.getBlock(x, y, z).stateId());
    }

    @Override
    public String getName() {
        return instance == null ? "unknown" : instance.getUuid().toString();
    }

    @Override
    public @Nullable UUID getUID() {
        return instance == null ? null : instance.getUuid();
    }

    @Override
    public PlatformChunk getChunkAt(int currChunkX, int currChunkZ) {
        return new MinestomPlatformChunk(instance == null ? null : instance.getChunk(currChunkX, currChunkZ));
    }

    @Override
    public boolean isLoaded() {
        return true;
    }
}
