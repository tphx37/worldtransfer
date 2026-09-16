package com.worldtransfer;

import com.worldtransfer.mixin.ScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorldTransferClient implements ClientModInitializer {
    private static Path receiptShownFor;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TransferNet.Blob.TYPE,
            (payload, context) -> IncomingTransfer.onBlob(payload));
        ClientPlayNetworking.registerGlobalReceiver(TransferNet.Control.TYPE,
            (payload, context) -> {
                if (context.client().player != null && !payload.detail().isEmpty()) {
                    context.client().player.sendSystemMessage(Component.literal(payload.detail()));
                }
            });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IncomingTransfer.tick();
            openOfferScreen(client);
            showHandoverReceipt(client);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PauseScreen)) {
                return;
            }
            Button transferButton = Button.builder(Component.literal("Transfer world ownership"),
                    button -> client.setScreenAndShow(new TransferScreen(screen)))
                .bounds(scaledWidth / 2 - 100, scaledHeight / 4 + 150, 200, 20)
                .build();
            transferButton.active = client.isLocalServer();
            ((ScreenAccessor) screen).worldtransfer$addRenderableWidget(transferButton);

            if (IncomingTransfer.current() != null) {
                Button incoming = Button.builder(Component.literal("Incoming world transfer"),
                        button -> client.setScreenAndShow(new IncomingTransferScreen(screen)))
                    .bounds(scaledWidth / 2 - 100, scaledHeight / 4 + 174, 200, 20)
                    .build();
                ((ScreenAccessor) screen).worldtransfer$addRenderableWidget(incoming);
            }
        });
    }

    /** Pops the offer up as soon as it lands, unless the player is busy in another screen. */
    private static void openOfferScreen(Minecraft client) {
        IncomingTransfer transfer = IncomingTransfer.current();
        if (transfer == null || transfer.state() != IncomingTransfer.State.OFFERED) {
            return;
        }
        if (client.player != null) {
            client.setScreenAndShow(new IncomingTransferScreen(null));
        }
    }

    /**
     * When a transferred world is opened, tell the player who handed it over - and warn them if the
     * save was meant for somebody else, which usually means the wrong ZIP got passed around.
     */
    private static void showHandoverReceipt(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.player == null) {
            return;
        }
        Path world = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
        if (world.equals(receiptShownFor)) {
            return;
        }
        Path marker = world.resolve(TransferData.MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        receiptShownFor = world;
        try {
            String json = Files.readString(marker);
            String newHost = TransferData.field(json, "newHost");
            String previousHost = TransferData.field(json, "previousHost");
            String createdAt = TransferData.field(json, "createdAt");
            String you = client.player.getGameProfile().name();
            if (!newHost.equalsIgnoreCase(you)) {
                client.player.sendSystemMessage(Component.literal(
                    "Warning: this save was handed to " + newHost + ", not to you. Your data may not be here; "
                        + "check that you were sent the right file."));
                return;
            }
            client.player.sendSystemMessage(Component.literal(
                "You are now the host of this world. Handed over by " + previousHost + " on " + createdAt
                    + ". Type the pause menu's transfer screen to see the full history."));
        } catch (Exception exception) {
            // A malformed marker is not worth interrupting the player over.
        }
    }
}
