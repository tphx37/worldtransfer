package com.worldtransfer;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Fabric API registration for the loader-neutral transfer payloads. */
final class FabricNetwork {
    private FabricNetwork() {
    }

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TransferNet.Blob.TYPE, TransferNet.Blob.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TransferNet.Control.TYPE, TransferNet.Control.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TransferNet.Reply.TYPE, TransferNet.Reply.CODEC);
    }
}