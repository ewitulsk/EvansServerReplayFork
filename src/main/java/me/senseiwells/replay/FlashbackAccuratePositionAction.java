package me.senseiwells.replay;

import net.casual.arcade.replay.util.flashback.FlashbackAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

/**
 * Writes the Flashback {@code action/accurate_player_position_optional} action.
 * <p>
 * Arcade's {@link FlashbackAction} enum does not include this action, so we
 * cannot go through {@code FlashbackChunkedWriter#writeAction}. Instead,
 * {@link me.senseiwells.replay.mixin.FlashbackChunkedWriterMixin} appends our
 * identifier to the action table in each chunk's header (making this the last
 * ordinal), and we emit raw action records of the form
 * {@code VarInt ordinal + Int size + payload}.
 * <p>
 * The payload matches Flashback's {@code FlashbackAccurateEntityPosition}
 * codec: {@code VarInt entityId, VarInt sampleCount, count*(double x, double y,
 * double z, float yaw, float pitch)}. Server-side we only know tick-boundary
 * positions, so we emit a 2-sample {@code prev -> curr} pair per tick which
 * Flashback's {@code AccurateEntityPositionHandler} linearly interpolates at
 * render rate — turning the 20Hz "slideshow" stepping into smooth motion.
 */
public final class FlashbackAccuratePositionAction {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(
        "flashback", "action/accurate_player_position_optional");

    // The action table in each chunk header lists the arcade enum entries
    // followed by our appended identifier, so our ordinal is the enum size.
    public static final int ORDINAL = FlashbackAction.getEntries().size();

    private FlashbackAccuratePositionAction() {
    }

    public static void emit(
        RegistryFriendlyByteBuf buffer, int entityId,
        double prevX, double prevY, double prevZ, float prevYaw, float prevPitch,
        double x, double y, double z, float yaw, float pitch
    ) {
        buffer.writeVarInt(ORDINAL);
        int sizeIndex = buffer.writerIndex();
        buffer.writeInt(0);
        buffer.writeVarInt(entityId);
        buffer.writeVarInt(2);
        writeSample(buffer, prevX, prevY, prevZ, prevYaw, prevPitch);
        writeSample(buffer, x, y, z, yaw, pitch);
        buffer.setInt(sizeIndex, buffer.writerIndex() - sizeIndex - 4);
    }

    private static void writeSample(RegistryFriendlyByteBuf buffer, double x, double y, double z, float yaw, float pitch) {
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(yaw);
        buffer.writeFloat(pitch);
    }
}
