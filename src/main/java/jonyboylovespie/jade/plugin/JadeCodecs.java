package jonyboylovespie.jade.plugin;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.server.MinecraftServer;

/**
 * Buffer helpers mirroring Jade's {@code Accessor#encodeAsNbt}: provider payloads are encoded with
 * vanilla stream codecs and stored in the response tag as a byte array.
 */
public final class JadeCodecs {
    public static RegistryFriendlyByteBuf newBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
    }

    public static RegistryFriendlyByteBuf wrap(byte[] data) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), MinecraftServer.getServer().registryAccess());
    }

    public static byte[] toByteArray(RegistryFriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    public static <D> ByteArrayTag encodeAsNbt(StreamEncoder<? super RegistryFriendlyByteBuf, D> codec, D value) {
        RegistryFriendlyByteBuf buf = newBuffer();
        try {
            codec.encode(buf, value);
            return new ByteArrayTag(toByteArray(buf));
        } finally {
            buf.release();
        }
    }

    private JadeCodecs() {
    }
}
