package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MCphoneNetwork {
    private static final Map<Class<?>, Outgoing<?>> C2S = new HashMap<>();
    private static final Map<Class<?>, Outgoing<?>> S2C = new HashMap<>();
    private static final List<Incoming<?>> CLIENT_RECEIVERS = new ArrayList<>();
    private static BiConsumer<ResourceLocation, FriendlyByteBuf> clientSender;

    private MCphoneNetwork() {}

    public static <T> void registerToServer(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        ResourceLocation channel = channel(type);
        add(C2S, type, new Outgoing<>(type, channel, encoder));
        ServerPlayNetworking.registerGlobalReceiver(channel, (server, player, connection, buffer, response) -> {
            T packet = decoder.apply(buffer);
            server.execute(() -> {
                if (player.isRemoved() || player.connection != connection) return;
                try {
                    handler.accept(packet, player);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    MCphone.LOGGER.error("MCphone packet handler failed: {}", channel, failure);
                }
            });
        });
    }

    public static <T> void registerToClient(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        ResourceLocation channel = channel(type);
        add(S2C, type, new Outgoing<>(type, channel, encoder));
        CLIENT_RECEIVERS.add(new Incoming<>(channel, decoder, handler));
    }

    public static void installClient(BiConsumer<ResourceLocation, FriendlyByteBuf> sender,
            Consumer<Incoming<?>> receiver) {
        clientSender = sender;
        CLIENT_RECEIVERS.forEach(receiver);
    }

    public static boolean canSend(ServerPlayer player, Class<?> type) {
        return ServerPlayNetworking.canSend(player, S2C.get(type).channel());
    }

    public static ResourceLocation serverChannel(Class<?> type) {
        return C2S.get(type).channel();
    }

    public static void sendToServer(Object packet) {
        var outgoing = C2S.get(packet.getClass());
        if (clientSender != null) clientSender.accept(outgoing.channel(), outgoing.encode(packet));
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        var outgoing = S2C.get(packet.getClass());
        if (ServerPlayNetworking.canSend(player, outgoing.channel())) {
            ServerPlayNetworking.send(player, outgoing.channel(), outgoing.encode(packet));
        }
    }

    // The prefix changes when the 1.20.1 wire format changes. Packet class names are retained in the JAR.
    private static ResourceLocation channel(Class<?> type) {
        return new ResourceLocation(MCphone.MODID, "f1201_v1/" + type.getSimpleName().toLowerCase(Locale.ROOT));
    }

    private static void add(Map<Class<?>, Outgoing<?>> map, Class<?> type, Outgoing<?> entry) {
        if (map.values().stream().anyMatch(existing -> existing.channel().equals(entry.channel()))) {
            throw new IllegalArgumentException("Duplicate MCphone channel " + entry.channel());
        }
        map.put(type, entry);
    }

    private record Outgoing<T>(Class<T> type, ResourceLocation channel, BiConsumer<T, FriendlyByteBuf> writer) {
        FriendlyByteBuf encode(Object packet) {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            writer.accept(type.cast(packet), buffer);
            return buffer;
        }
    }

    public record Incoming<T>(ResourceLocation channel, Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {}
}
