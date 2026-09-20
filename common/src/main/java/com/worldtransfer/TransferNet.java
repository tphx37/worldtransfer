package com.worldtransfer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * The wire protocol for handing a world over the LAN connection itself.
 *
 * <p>Three payloads are enough for the whole exchange:</p>
 * <ul>
 *   <li>{@link Blob} (server → client): a numbered chunk of one logical stream. Stream 0 is the
 *       world index, stream 1 is the file bundle.</li>
 *   <li>{@link Control} (server → client): short status/cancel messages.</li>
 *   <li>{@link Reply} (client → server): accept/decline, the chunked list of files the receiver
 *       actually needs, and the final result.</li>
 * </ul>
 *
 * <p>Chunk sizes are dictated by vanilla: a clientbound custom payload may be up to 1 MiB, a
 * serverbound one only 32767 bytes. Hence the two different limits below.</p>
 */
public final class TransferNet {
    public static final int S2C_CHUNK = 256 * 1024;
    public static final int C2S_CHUNK = 16 * 1024;

    public static final int STREAM_INDEX = 0;
    public static final int STREAM_BUNDLE = 1;

    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";
    public static final String NEED = "need";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    private TransferNet() {
    }

    public record Blob(UUID transferId, int stream, int seq, boolean last, byte[] chunk)
        implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Blob> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WorldTransferConstants.MOD_ID, "blob"));

        public static final StreamCodec<FriendlyByteBuf, Blob> CODEC = CustomPacketPayload.codec(
            (value, buffer) -> {
                buffer.writeUUID(value.transferId());
                buffer.writeVarInt(value.stream());
                buffer.writeVarInt(value.seq());
                buffer.writeBoolean(value.last());
                buffer.writeByteArray(value.chunk());
            },
            buffer -> new Blob(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readBoolean(), buffer.readByteArray(S2C_CHUNK + 1024)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Control(UUID transferId, String action, String detail) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Control> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WorldTransferConstants.MOD_ID, "control"));

        public static final StreamCodec<FriendlyByteBuf, Control> CODEC = CustomPacketPayload.codec(
            (value, buffer) -> {
                buffer.writeUUID(value.transferId());
                buffer.writeUtf(value.action(), 64);
                buffer.writeUtf(value.detail(), 512);
            },
            buffer -> new Control(buffer.readUUID(), buffer.readUtf(64), buffer.readUtf(512)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Reply(UUID transferId, String action, int seq, boolean last, byte[] chunk)
        implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Reply> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(WorldTransferConstants.MOD_ID, "reply"));

        public static final StreamCodec<FriendlyByteBuf, Reply> CODEC = CustomPacketPayload.codec(
            (value, buffer) -> {
                buffer.writeUUID(value.transferId());
                buffer.writeUtf(value.action(), 64);
                buffer.writeVarInt(value.seq());
                buffer.writeBoolean(value.last());
                buffer.writeByteArray(value.chunk());
            },
            buffer -> new Reply(buffer.readUUID(), buffer.readUtf(64), buffer.readVarInt(),
                buffer.readBoolean(), buffer.readByteArray(C2S_CHUNK + 512)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static Reply simple(UUID transferId, String action, String detail) {
            return new Reply(transferId, action, 0, true,
                detail.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public String detail() {
            return new String(chunk, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}
