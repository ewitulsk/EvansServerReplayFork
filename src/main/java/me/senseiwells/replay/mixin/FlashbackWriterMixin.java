package me.senseiwells.replay.mixin;

import me.senseiwells.replay.FlashbackAccuratePositionAction;
import net.casual.arcade.replay.io.writer.flashback.FlashbackChunkedWriter;
import net.casual.arcade.replay.io.writer.flashback.FlashbackWriter;
import net.casual.arcade.replay.recorder.ReplayRecorder;
import net.casual.arcade.replay.recorder.player.ReplayPlayerRecorder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/**
 * Emits {@code action/accurate_player_position_optional} for the recorded
 * player each tick. Arcade's writer only produces 20Hz {@code move_entities}
 * snapshots, and the recorded player is never sent position packets about
 * itself, so its camera steps once per tick during Flashback playback.
 * Feeding Flashback a {@code prev -> curr} sample pair per tick lets its
 * {@code AccurateEntityPositionHandler} interpolate the camera at render rate.
 * <p>
 * All writes to the chunk buffer must go through the writer's executor:
 * {@code FlashbackWriter} serializes its own {@code writeAction} calls there,
 * and writing directly from the server thread interleaves our bytes into the
 * middle of other actions' payloads. Conversely, the writer's {@code positions}
 * map is mutated by its executor tasks, so it must not be read from the server
 * thread - we read the live player entity instead, which is server-thread safe.
 * <p>
 * Injection point is after {@code writeEntityMovement}: the emit must be
 * queued before the {@code next_tick} action so the samples are processed in
 * the tick they describe.
 */
@Mixin(FlashbackWriter.class)
public abstract class FlashbackWriterMixin {

    @Shadow private ReplayRecorder recorder;
    @Shadow private FlashbackChunkedWriter writer;
    @Shadow private ExecutorService executor;

    // Recorded player's last tick {x, y, z, yaw, pitch}
    @Unique private double[] serverreplay$lastPlayerPosition;

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/casual/arcade/replay/io/writer/flashback/FlashbackWriter;writeEntityMovement()V",
        shift = At.Shift.AFTER), remap = false)
    private void serverreplay$writeAccuratePositions(CallbackInfo ci) {
        if (!(this.recorder instanceof ReplayPlayerRecorder playerRecorder)) {
            return;
        }
        ServerPlayer player;
        try {
            player = playerRecorder.getPlayerOrThrow();
        } catch (IllegalStateException e) {
            return; // Player not yet spawned; skip until next tick.
        }

        double[] now = { player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot() };
        double[] previous = this.serverreplay$lastPlayerPosition;
        this.serverreplay$lastPlayerPosition = now;
        if (previous == null || Arrays.equals(previous, now)) {
            return;
        }

        int entityId = player.getId();
        this.executor.execute(() -> {
            RegistryFriendlyByteBuf buffer = ((FlashbackChunkedWriterAccessor) (Object) this.writer).getBuffer();
            FlashbackAccuratePositionAction.emit(buffer, entityId,
                previous[0], previous[1], previous[2], (float) previous[3], (float) previous[4],
                now[0], now[1], now[2], (float) now[3], (float) now[4]);
        });
    }
}
