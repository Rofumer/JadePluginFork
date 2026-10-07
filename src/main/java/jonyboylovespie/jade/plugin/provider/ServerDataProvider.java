package jonyboylovespie.jade.plugin.provider;

import java.util.function.Function;
import jonyboylovespie.jade.plugin.JadeCodecs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamEncoder;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface ServerDataProvider<A> {
    void appendServerData(String uid, CompoundTag data, A accessor);

    /**
     * Equivalent of Jade's {@code StreamServerDataProvider}: the value returned by {@code getter} is encoded with
     * {@code codec} and stored under the provider id. Nothing is written when the getter returns {@code null}.
     */
    static <A, D> ServerDataProvider<A> stream(StreamEncoder<? super RegistryFriendlyByteBuf, D> codec, Function<A, @Nullable D> getter) {
        return (uid, data, accessor) -> {
            D value = getter.apply(accessor);
            if (value != null) {
                data.put(uid, JadeCodecs.encodeAsNbt(codec, value));
            }
        };
    }
}
