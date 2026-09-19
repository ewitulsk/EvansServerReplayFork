package me.senseiwells.replay.mixin;

import net.casual.arcade.replay.io.writer.flashback.FlashbackChunkedWriter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FlashbackChunkedWriter.class)
public interface FlashbackChunkedWriterAccessor {

    @Accessor("buffer")
    RegistryFriendlyByteBuf getBuffer();
}
