package me.senseiwells.replay.mixin;

import kotlin.enums.EnumEntries;
import me.senseiwells.replay.FlashbackAccuratePositionAction;
import net.casual.arcade.replay.io.writer.flashback.FlashbackChunkedWriter;
import net.casual.arcade.replay.util.flashback.FlashbackAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Appends {@code flashback:action/accurate_player_position_optional} to the
 * action identifier table written in each replay chunk's header. The appended
 * entry's ordinal is {@code FlashbackAction.getEntries().size()}, matching
 * {@link FlashbackAccuratePositionAction#ORDINAL}.
 */
@Mixin(FlashbackChunkedWriter.class)
public abstract class FlashbackChunkedWriterMixin {

    @Redirect(method = "writeHeader", at = @At(value = "INVOKE",
        target = "Lkotlin/enums/EnumEntries;size()I"), remap = false)
    private int serverreplay$extendActionCount(EnumEntries<FlashbackAction> entries) {
        return entries.size() + 1;
    }

    @Inject(method = "writeHeader", at = @At("TAIL"), remap = false)
    private void serverreplay$appendActionId(CallbackInfo ci) {
        ((FlashbackChunkedWriterAccessor) this).getBuffer()
            .writeIdentifier(FlashbackAccuratePositionAction.ID);
    }
}
