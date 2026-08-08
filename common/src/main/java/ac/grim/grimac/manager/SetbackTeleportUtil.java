package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimTeleportEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsN;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineElytra;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineNormal;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineWater;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Location;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SetbackTeleportUtil extends Check implements PostPredictionCheck {
    // Sync to netty
    public final ConcurrentLinkedQueue<TeleportData> pendingTeleports = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();
    private static final GrimTeleportEvent.Channel TELEPORT_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(GrimTeleportEvent.class);
    private static final GrimPlayerSetbackEvent.Channel PLAYER_SETBACK_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(GrimPlayerSetbackEvent.class);
    // Sync to netty, a player MUST accept a teleport to spawn into the world
    // A teleport is used to end the loading screen.  Some cheats pretend to never end the loading screen
    // in an attempt to disable the anticheat.  Be careful.
    // We fix this by blocking serverbound movements until the player is out of the loading screen.
    public boolean hasAcceptedSpawnTeleport = false;
    // Was there a ghost block that forces us to block offsets until the player accepts their teleport?
    public boolean blockOffsets = false;
    public SetbackPosWithVector lastKnownGoodPosition;
    // Are we currently sending setback stuff?
    public boolean isSendingSetback = false;
    public int cheatVehicleInterpolationDelay = 0;
    // This required setback data is the head of the teleport.
    // It is set by both bukkit and netty due to going on the bukkit thread to setback players
    @Getter
    private SetBackData requiredSetBack = null;
    private long lastWorldResync = 0;
    /** Minestom: Zeitpunkt (ms), zu dem der aktuelle native Rubberband-Teleport losgeschickt wurde (Completion-Timeout). */
    private long minestomSetbackSentAt = 0;
    /** Test-only: Rubberband-Debug-Log (nur mit -Dgrim.rubberband.debug=true; auf Prod aus). */
    private static final boolean RB_DEBUG = Boolean.getBoolean("grim.rubberband.debug");

    public SetbackTeleportUtil(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // Minestom: laufenden nativen Rubberband-Setback ggf. abschließen (Spieler wieder an Zielpos.).
        // Muss VOR dem lastKnownGoodPosition-Update laufen, damit der Anker erst nach Completion vorrückt.
        if (ac.grim.grimac.utils.latency.CompensatedWorld.usePlatformWorldFallback) {
            tickMinestomSetbackCompletion();
        }

        // Grab friction now when we know player on ground and other variables
        Vector3dm afterTickFriction = player.clientVelocity.clone();

        // We must first check if the player has accepted their setback
        // If the setback isn't complete, then this position is illegitimate
        if (predictionComplete.getData().getSetback() != null) {
            // The player needs to now wait for their vehicle to go into the right place before getting back in
            if (cheatVehicleInterpolationDelay > 0) cheatVehicleInterpolationDelay = 10;
            // Teleport, let velocity be reset
            lastKnownGoodPosition = new SetbackPosWithVector(new Vector3d(player.x, player.y, player.z), afterTickFriction);
        } else if (requiredSetBack == null || requiredSetBack.isComplete()) {
            cheatVehicleInterpolationDelay--;
            // No simulation... we can do that later. We just need to know the valid position.
            // As we didn't setback here, the new position is known to be safe!
            lastKnownGoodPosition = new SetbackPosWithVector(new Vector3d(player.x, player.y, player.z), afterTickFriction);
        }

        if (requiredSetBack != null) requiredSetBack.tick();
    }

    public void executeForceResync() {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return; // We don't care about spectators, they don't flag
        if (lastKnownGoodPosition == null) return; // Player hasn't spawned yet
        blockMovementsUntilResync(true, true);
    }

    public void executeNonSimulatingForceResync() {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return; // We don't care about spectators, they don't flag
        if (lastKnownGoodPosition == null) return; // Player hasn't spawned yet
        blockMovementsUntilResync(false, true);
    }

    public void executeNonSimulatingSetback() {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return; // We don't care about spectators, they don't flag
        if (lastKnownGoodPosition == null) return; // Player hasn't spawned yet
        blockMovementsUntilResync(false, false);
    }

    public boolean executeViolationSetback() {
        if (isExempt()) return false;
        blockMovementsUntilResync(true, false);
        return true;
    }

    private boolean isExempt() {
        // Not exempting spectators here because timer check for spectators is actually valid.
        // Player hasn't spawned yet
        if (lastKnownGoodPosition == null) return true;
        // Setbacks aren't allowed
        if (player.disableGrim) return true;
        // Player has permission to cheat, permission not given to OP by default.
        return player.platformPlayer != null && player.noSetbackPermission;
    }

    private void simulateFriction(Vector3dm vector) {
        // We must always do this before simulating positions, as this is the last actual (safe) movement
        // We must not do this for knockback or explosions, as they are at the start of the tick
        if (player.wasTouchingWater) {
            PredictionEngineWater.staticVectorEndOfTick(player, vector, 0.8F, player.gravity, true);
        } else if (player.wasTouchingLava) {
            vector.multiply(0.5D);
            if (player.hasGravity)
                vector.add(0.0D, -player.gravity / 4.0D, 0.0D);
        } else if (player.isGliding) {
            PredictionEngineElytra.getElytraMovement(player, vector, ReachUtils.getLook(player, player.yaw, player.pitch)).multiply(player.stuckSpeedMultiplier).multiply(0.99F, 0.98F, 0.99F);
            vector.setY(vector.getY() - 0.05); // Make the player fall a bit
        } else { // Gliding doesn't have friction, we handle it differently
            PredictionEngineNormal.staticVectorEndOfTick(player, vector); // Lava and normal movement
        }

        // Prevent abusing setbacks to move out of blocks like webs
        vector.multiply(player.stuckSpeedMultiplier);

        // stop 1.8 players from stepping onto 1.25 high blocks, because why not?
        new PredictionEngine().applyMovementThreshold(player, new HashSet<>(Collections.singletonList(new VectorData(vector, VectorData.VectorType.BestVelPicked))));
    }

    private void blockMovementsUntilResync(boolean simulateNextTickPosition, boolean isResync) {
        // Minestom: Grims paket-basierter Setback-Teleport wird auf dem NIO-Port nie per Transaktion
        // bestätigt (checkTeleportQueue matcht nie) -> requiredSetBack bliebe ewig "incomplete" ->
        // Spieler serverseitig festgehängt. Stattdessen fahren wir den Setback über Minestoms NATIVEN
        // Teleport (player.teleport(Pos)): der Client bestätigt ihn regulär, das zurückgegebene Future
        // schließt genau dann ab -> das ist unser Resync-Signal. So wirkt Grims eigene Setback-
        // Entscheidung (nur bei sicheren Verstößen, setback=true) als echter Rubberband wie auf Bukkit,
        // ohne Festhängen. Legitimes Rest-Rauschen flaggt mit setback=false und kommt hier nie an.
        if (ac.grim.grimac.utils.latency.CompensatedWorld.usePlatformWorldFallback) {
            minestomRubberband();
            return;
        }
        if (requiredSetBack == null) return; // Hasn't spawned
        if (player.platformPlayer != null && player.noSetbackPermission)
            return; // The player has permission to cheat
        requiredSetBack.setPlugin(false); // The player has illegal movement, block from vanilla ac override
        if (isPendingSetback()) return; // Don't spam setbacks

        // Only let us full resync once every five seconds to prevent unneeded bukkit load
        if (System.currentTimeMillis() - lastWorldResync > 5 * 1000) {
            player.resyncPositions(player.boundingBox.copy().expand(1));
            lastWorldResync = System.currentTimeMillis();
        }

        Vector3dm clientVel = lastKnownGoodPosition.vector.clone();

        Pair<VelocityData, Vector3dm> futureKb = player.checkManager.getKnockbackHandler().getFutureKnockback();
        VelocityData futureExplosion = player.checkManager.getExplosionHandler().getFutureExplosion();

        // Velocity sets
        // Don't let player reuse setback velocity
        if (futureKb.first() != null && !futureKb.first().isSetback) {
            clientVel = futureKb.second();
        }

        // Explosion adds
        if (futureExplosion != null && (futureKb.first() == null
                || (futureKb.first().transaction < futureExplosion.transaction && !futureKb.first().isSetback))) {
            clientVel.add(futureExplosion.vector);
        }

        Vector3d position = lastKnownGoodPosition.pos;

        SimpleCollisionBox oldBB = player.boundingBox;
        player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, position.getX(), position.getY(), position.getZ());

        // Mini prediction engine - simulate collisions
        if (simulateNextTickPosition) {
            Vector3dm collide = Collisions.collide(player, clientVel.getX(), clientVel.getY(), clientVel.getZ());

            position = position.withX(position.getX() + collide.getX());
            position = position.withY(position.getY() + collide.getY());
            // TODO: Is this even needed? Can't reproduce any phasing on vanilla 1.8 when being setback.
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
                // 1.8 players need the collision epsilon to not phase into blocks when being setback
                // Due to simulation, this will not allow a flight bypass by sending a billion invalid movements
                position = position.withY(position.getY() + SimpleCollisionBox.COLLISION_EPSILON);
            }
            position = position.withZ(position.getZ() + collide.getZ());

            if (clientVel.getX() != collide.getX()) {
                clientVel.setX(BlockProperties.getVelocityAfterHorizontalCollision(player, clientVel.getX()));
            }
            if (clientVel.getY() != collide.getY()) {
                clientVel.setY(BlockProperties.getVelocityAfterVerticalCollision(player, clientVel.getY(), collide.getY()));
            }
            if (clientVel.getZ() != collide.getZ()) {
                clientVel.setZ(BlockProperties.getVelocityAfterHorizontalCollision(player, clientVel.getZ()));
            }

            simulateFriction(clientVel);
        }

        player.boundingBox = oldBB; // reset back to the new bounding box

        if (!hasAcceptedSpawnTeleport || player.isFlying)
            clientVel = null; // if the player is flying or hasn't spawned... don't force kb

        // Something weird has occurred in the player's movement, block offsets until we resync
        if (isResync) {
            blockOffsets = true;
        }

        SetBackData data = new SetBackData(new TeleportData(position, 0, 0, null, RelativeFlag.YAW.or(RelativeFlag.PITCH), player.lastTransactionSent.get(), 0), player.yaw, player.pitch, clientVel, player.inVehicle(), false);
        sendSetback(data);
    }

    /**
     * Minestom-nativer Rubberband-Setback. Grims paket-basierter Setback ({@link #sendSetback}) wird auf
     * dem NIO-Port nie per Transaktion bestätigt ({@link #checkTeleportQueue} matcht nie), sodass
     * {@code requiredSetBack} ewig „incomplete" bliebe und den Spieler serverseitig festhielte. Hier wird
     * stattdessen über Minestoms <b>native</b> Teleport-API auf Grims sichere Position
     * ({@link #lastKnownGoodPosition}) zurückgesetzt — der Client bestätigt diesen Teleport regulär.
     *
     * <p>{@code requiredSetBack} wird als Freeze-Latch gesetzt: {@link #onPredictionComplete} rückt den
     * Anker erst wieder vor, wenn der Setback complete ist. Die Completion passiert thread-sicher auf
     * Grims Thread in {@link #onPredictionComplete} (Spieler nahe Zielposition → Client hat bestätigt),
     * nicht im Async-Teleport-Callback.
     */
    /** Basis-Fenster (ms) nach server-seitiger Velocity, in dem IMMER exempt (kurzer Impuls, auch am Boden). */
    private static final long VELOCITY_GRACE_MS = 1500L;
    /** Hard-Cap (ms): bis hierhin nach einem Impuls exempt, SOLANGE der Spieler noch in der Luft ist. */
    private static final long VELOCITY_AIRBORNE_CAP_MS = 6000L;

    private void minestomRubberband() {
        if (isExempt()) return; // Spectator/disableGrim/noSetbackPermission/nicht gespawnt
        if (player.platformPlayer == null) return;
        if (isPendingSetback()) return; // Ein nativer Setback ist noch unterwegs → nicht spammen (RTT-Drossel)
        // Legitimer server-seitiger Impuls (Knockback/Boost per EntityVelocity): Grim rechnet den Bogen
        // auf dem Port nicht sauber ein, also nicht zurücksetzen. Ein big Knockback/Boost fliegt+FÄLLT
        // länger als das Basis-Fenster; solange der Spieler danach noch in der Luft ist (fällt), bis zum
        // Hard-Cap exempt bleiben. Am Boden endet die Kulanz sofort. Ein Fly-Cheater hat keine
        // server-Velocity → sinceVel ist riesig → beide Bedingungen false → wird weiter gefangen.
        final long sinceVel = System.currentTimeMillis() - player.lastServerVelocityMillis;
        if (sinceVel < VELOCITY_GRACE_MS) return;
        if (sinceVel < VELOCITY_AIRBORNE_CAP_MS && !player.onGround) return;

        final Vector3d safe = lastKnownGoodPosition.pos;
        final TeleportData td = new TeleportData(new Vector3d(safe.getX(), safe.getY(), safe.getZ()),
                player.yaw, player.pitch, null, RelativeFlag.YAW.or(RelativeFlag.PITCH),
                player.lastTransactionSent.get(), 0);
        requiredSetBack = new SetBackData(td, player.yaw, player.pitch, null, player.inVehicle(), false);
        minestomSetbackSentAt = System.currentTimeMillis();

        // Test-Diagnose (nur mit -Dgrim.rubberband.debug=true, also im anticheat-devrun-Harness, NICHT
        // auf Prod): zeigt jeden Rubberband + die erhaltene Blickrichtung, um die View-Preservation und
        // das Streak-Gating zu verifizieren.
        if (RB_DEBUG) {
            System.out.printf("[RB-DEBUG] setback %s -> (%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f (View bleibt erhalten)%n",
                    player.user == null ? "?" : player.user.getName(),
                    safe.getX(), safe.getY(), safe.getZ(), player.yaw, player.pitch);
        }

        // Semantisches Setback-Event für Observability (wie im Bukkit-Pfad).
        PLAYER_SETBACK_CHANNEL.fire(player, 0, safe.getX(), safe.getY(), safe.getZ(), minestomSetbackSentAt);

        // Nativer Teleport = regulärer Client-Handshake, kein Festhängen. Completion erfolgt positions-
        // basiert in onPredictionComplete (thread-sicher), daher hier fire-and-forget.
        // WICHTIG: auf den Tick-Thread dispatchen. Grims Checks laufen auf dem Paket-Feeder-Thread, wo
        // der Minestom-Player transient instance==null zeigt -> player.teleport() würfe dort
        // "setInstance before teleporting". Der EntityScheduler (delay 0) führt auf dem Tick-Thread aus,
        // wo die Instance gültig ist. Yaw/Pitch aus der aktuellen Blickrichtung -> teleportAsync behält
        // die View (new Pos ohne yaw/pitch würde sie auf 0/0 schnappen).
        final Location target = new Location(null, safe.getX(), safe.getY(), safe.getZ(), player.yaw, player.pitch);
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(
                player.platformPlayer, GrimAPI.INSTANCE.getGrimPlugin(),
                () -> {
                    if (player.platformPlayer != null) player.platformPlayer.teleportAsync(target);
                }, null, 0);
    }

    /**
     * Minestom: Completet einen laufenden Rubberband-Setback thread-sicher auf Grims Thread, sobald der
     * Spieler (nach der Client-Teleport-Bestätigung) wieder an der Zielposition ist — oder nach einem
     * Timeout als Sicherung gegen ein verpasstes Positions-Match (verhindert dauerhaftes Festhängen).
     */
    private void tickMinestomSetbackCompletion() {
        if (requiredSetBack == null || requiredSetBack.isComplete()) return;
        final Vector3d target = requiredSetBack.getTeleportData().getLocation();
        final double dx = player.x - target.getX();
        final double dy = player.y - target.getY();
        final double dz = player.z - target.getZ();
        final boolean nearTarget = dx * dx + dy * dy + dz * dz < 0.25; // innerhalb ~0,5 Block
        if (nearTarget || System.currentTimeMillis() - minestomSetbackSentAt > 1500) {
            requiredSetBack.setComplete(true);
        }
    }

    private void sendSetback(SetBackData data) {
        // Minestom: no setback teleport (handshake can't complete -> stuck players). Defend via kick.
        if (ac.grim.grimac.utils.latency.CompensatedWorld.usePlatformWorldFallback) return;
        isSendingSetback = true;
        Vector3d position = data.getTeleportData().getLocation();

        try {
            // Player is in a vehicle
            if (player.inVehicle()) {
                int vehicleId = player.getRidingVehicleId();
                if (player.compensatedEntities.serverPlayerVehicle != null) {
                    // Dismount player from vehicle
                    if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)) {
                        player.user.sendPacket(new WrapperPlayServerSetPassengers(vehicleId, new int[2]));
                    } else {
                        player.user.sendPacket(new WrapperPlayServerAttachEntity(vehicleId, -1, false));
                    }

                    // Stop the player from being able to teleport vehicles and simply re-enter them to continue,
                    // therefore, teleport the entity
                    player.user.sendPacket(new WrapperPlayServerEntityTeleport(vehicleId, new Vector3d(position.getX(), position.getY(), position.getZ()), player.yaw % 360, 0, false));
                    player.getSetbackTeleportUtil().cheatVehicleInterpolationDelay = Integer.MAX_VALUE; // Set to max until player accepts the new position

                    // Make sure bukkit also knows the player got teleported out of their vehicle, can't do this async
                    GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(player.platformPlayer, GrimAPI.INSTANCE.getGrimPlugin(), () -> {
                        if (player.platformPlayer != null) {
                            GrimEntity vehicle = player.platformPlayer.getVehicle();
                            if (vehicle != null) {
                                vehicle.eject();
                            }
                        }
                    }, null, 0);
                }
            }

            double y = position.getY();
            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_7_10)) {
                y += 1.62; // 1.7 teleport offset if grim ever supports 1.7 again
            }

            // Send a transaction now to make sure there's always transactions around teleport
            player.sendTransaction();

            // Min value is 10000000000000000000000000000000 in binary, this makes sure the number is always < 0
            int teleportId = random.nextInt() | Integer.MIN_VALUE;
            data.setPlugin(false);
            data.getTeleportData().setTeleportId(teleportId);
            data.getTeleportData().setTransaction(player.lastTransactionSent.get());

            // Use provided transaction ID to make sure it can never desync, although there's no reason to do this
            addSentTeleport(new Location(null, position.getX(), y, position.getZ()),
                    null, data.getTeleportData().getTransaction(), RelativeFlag.YAW.or(RelativeFlag.PITCH), false, teleportId);
            // This must be done after setting the sent teleport, otherwise we lose velocity data
            requiredSetBack = data;
            // Send after tracking to fix race condition
            PacketEvents.getAPI().getProtocolManager().sendPacketSilently(player.user.getChannel(), new WrapperPlayServerPlayerPositionAndLook(position.getX(), position.getY(), position.getZ(), 0, 0, data.getTeleportData().getFlags().getMask(), teleportId, false));
            // Dual fire: packet-level signal for anticheat compat (GrimTeleportEvent),
            // semantic signal for admin/observability consumers (GrimPlayerSetbackEvent).
            long now = System.currentTimeMillis();
            TELEPORT_CHANNEL.fire(player, teleportId, now);
            PLAYER_SETBACK_CHANNEL.fire(player, teleportId, position.getX(), position.getY(), position.getZ(), now);
            player.sendTransaction();

            if (data.getVelocity() != null && data.getVelocity().lengthSquared() > 0) {
                player.user.sendPacket(new WrapperPlayServerEntityVelocity(player.entityID, new Vector3d(data.getVelocity().getX(), data.getVelocity().getY(), data.getVelocity().getZ())));
            }
        } finally {
            isSendingSetback = false;
        }
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public TeleportAcceptData checkTeleportQueue(double x, double y, double z, float yaw, float pitch) {
        // Support teleports without teleport confirmations
        // If the player is in a vehicle when teleported, they will exit their vehicle
        TeleportAcceptData teleportData = new TeleportAcceptData();

        TeleportData teleportPos;
        while ((teleportPos = pendingTeleports.peek()) != null) {
            double trueTeleportX = (teleportPos.isRelativeX() ? player.x : 0) + teleportPos.getLocation().getX();
            double trueTeleportY = (teleportPos.isRelativeY() ? player.y : 0) + teleportPos.getLocation().getY();
            double trueTeleportZ = (teleportPos.isRelativeZ() ? player.z : 0) + teleportPos.getLocation().getZ();

            // There seems to be a version difference in teleports past 30 million... just clamp the vector
            Vector3d clamped = VectorUtils.clampVector(new Vector3d(trueTeleportX, trueTeleportY, trueTeleportZ));
            double threshold = teleportPos.isRelativePos() ? player.getMovementThreshold() : 0;
            boolean closeEnoughY = Math.abs(clamped.getY() - y) <= 1e-7 + threshold; // 1.7 rounding
            // rotations are updated every frame, we can't accurately check them if they're relative
            boolean correctRotations = (yaw == teleportPos.getYaw() || teleportPos.isRelativeYaw())
                    && (pitch == teleportPos.getPitch() || teleportPos.isRelativePitch());

            if (player.lastTransactionReceived.get() == teleportPos.getTransaction() && Math.abs(clamped.getX() - x) <= threshold && closeEnoughY && Math.abs(clamped.getZ() - z) <= threshold && correctRotations) {
                pendingTeleports.poll();
                hasAcceptedSpawnTeleport = true;
                blockOffsets = false;

                // Player has accepted their setback!
                // We can compare transactions to check if equals because each teleport gets its own transaction
                if (requiredSetBack != null && requiredSetBack.getTeleportData().getTransaction() == teleportPos.getTransaction()) {
                    teleportData.setSetback(requiredSetBack);
                    requiredSetBack.setComplete(true);
                }

                teleportData.setTeleportData(teleportPos);
                teleportData.setTeleport(true);
                break;
            } else if (player.lastTransactionReceived.get() > teleportPos.getTransaction()) {
                // The player ignored the teleport (and this teleport matters), resynchronize
                player.checkManager.getCheck(BadPacketsN.class).flag();
                pendingTeleports.poll();
                requiredSetBack.setPlugin(false);
                if (pendingTeleports.isEmpty()) {
                    sendSetback(requiredSetBack);
                }
                continue;
            }
            // No farther setbacks before the player's transaction
            break;
        }

        return teleportData;
    }

    /**
     * @param x - Player X position
     * @param y - Player Y position
     * @param z - Player Z position
     * @return - Whether the player has completed a teleport by being at this position
     */
    public boolean checkVehicleTeleportQueue(double x, double y, double z) {
        int lastTransaction = player.lastTransactionReceived.get();

        while (true) {
            IntToObjectPair<Vector3d> teleportPos = player.vehicleData.vehicleTeleports.peek();
            if (teleportPos == null) break;
            if (lastTransaction < teleportPos.first()) {
                break;
            }

            Vector3d position = teleportPos.second();
            if (position.getX() == x && position.getY() == y && position.getZ() == z) {
                player.vehicleData.vehicleTeleports.poll();

                return true;
            } else if (lastTransaction > teleportPos.first() + 1) {
                player.vehicleData.vehicleTeleports.poll();

                // Vehicles have terrible netcode so just ignore it if the teleport wasn't from us setting the player back
                // Players don't have to respond to vehicle teleports if they aren't controlling the entity anyways
                continue;
            }

            break;
        }

        return false;
    }

    /**
     * @return If the player is in a desync state and is waiting on information from the server
     */
    public boolean shouldBlockMovement() {
        // Minestom: never block/hold client movement. Setback CAN'T complete here (the teleport-accept
        // handshake never matches on the port), so a triggered setback would leave requiredSetBack
        // forever-incomplete => the player is stuck server-side even after they stop cheating. We defend
        // via kick instead (see GrimEnforcement), so movement must always pass through.
        if (ac.grim.grimac.utils.latency.CompensatedWorld.usePlatformWorldFallback) {
            return false;
        }
        // This is required to ensure protection from servers teleporting from CREATIVE to SURVIVAL
        // I should likely refactor
        return insideUnloadedChunk() || blockOffsets || (requiredSetBack != null && !requiredSetBack.isComplete());
    }

    private boolean isPendingSetback() {
        // Relative setbacks shouldn't count
        if (requiredSetBack != null && (requiredSetBack.getTeleportData().isRelativeX() || requiredSetBack.getTeleportData().isRelativeY() || requiredSetBack.getTeleportData().isRelativeZ())) {
            return false;
        }
        // The setback is not complete
        return requiredSetBack != null && !requiredSetBack.isComplete();
    }

    /**
     * When the player is inside an unloaded chunk, they simply fall through the void which shouldn't be checked
     *
     * @return Whether the player has loaded the chunk and accepted a teleport to correct movement or not
     */
    public boolean insideUnloadedChunk() {
        int chunkX = GrimMath.floor(player.x) >> 4;
        int chunkZ = GrimMath.floor(player.z) >> 4;
        Column column = player.compensatedWorld.getChunk(chunkX, chunkZ);

        boolean replicaMissing = column == null || column.transaction() >= player.lastTransactionReceived.get();

        // Minestom: Grim's packet-based chunk replica is never populated, so `column` is always null.
        // Without this, insideUnloadedChunk() is permanently true → shouldBlockMovement() cancels EVERY
        // movement packet → the player is stuck server-side at spawn while Grim tracks the real (moving)
        // position → massive Simulation/GroundSpoof desync. Consult the live instance: if it has the
        // chunk loaded, the player is NOT in an unloaded chunk.
        if (replicaMissing
                && ac.grim.grimac.utils.latency.CompensatedWorld.usePlatformWorldFallback
                && player.platformPlayer != null) {
            try {
                if (player.platformPlayer.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    replicaMissing = false;
                }
            } catch (Throwable ignored) {
            }
        }

        // If true, the player is in an unloaded chunk
        return !player.disableGrim && (replicaMissing ||
                // The player hasn't loaded past the DOWNLOADING TERRAIN screen
                !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport);
    }

    public void addSentTeleport(Location position, @Nullable Vector3d velocity, int transaction, RelativeFlag flags, boolean plugin, int teleportId) {
        // Clients below 1.21.2 do not have this.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) {
            velocity = null;
        }

        TeleportData data = new TeleportData(
                new Vector3d(position.getX(), position.getY(), position.getZ()),
                position.getYaw(),
                position.getPitch(),
                velocity,
                flags,
                transaction,
                teleportId
        );
        pendingTeleports.add(data);

        Vector3d safePosition = new Vector3d(position.getX(), position.getY(), position.getZ());

        // We must convert relative teleports to avoid them becoming client controlled in the case of setback
        if (flags.has(RelativeFlag.X)) {
            safePosition = safePosition.withX(safePosition.getX() + lastKnownGoodPosition.pos.getX());
        }

        if (flags.has(RelativeFlag.Y)) {
            safePosition = safePosition.withY(safePosition.getY() + lastKnownGoodPosition.pos.getY());
        }

        if (flags.has(RelativeFlag.Z)) {
            safePosition = safePosition.withZ(safePosition.getZ() + lastKnownGoodPosition.pos.getZ());
        }

        data = new TeleportData(safePosition, 0, 0, velocity, RelativeFlag.YAW.or(RelativeFlag.PITCH), transaction, teleportId);
        requiredSetBack = new SetBackData(data, player.yaw, player.pitch, null, false, plugin);

        this.lastKnownGoodPosition = new SetbackPosWithVector(safePosition, new Vector3dm());
    }

    @AllArgsConstructor
    @Getter
    @Setter
    public static class SetbackPosWithVector {
        private final Vector3d pos;
        private Vector3dm vector;
    }
}
