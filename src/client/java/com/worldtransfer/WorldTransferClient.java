package com.worldtransfer;

import com.worldtransfer.mixin.ScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
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
            if (!(screen instanceof PauseScreen pauseScreen) || !pauseScreen.showsPauseMenu()) {
                return;
            }
            // Anchor right next to "Save and Quit to Title" so it reads as one of the row's own
            // buttons instead of a whole extra menu row. Falls back to a fixed guess if that button
            // cannot be found (e.g. a future game update changes the pause menu layout).
            int x = scaledWidth / 2 + 106;
            int y = scaledHeight / 4 + 150 + 66;
            AbstractWidget disconnect = findDisconnectButton(pauseScreen, client.isLocalServer());
            if (disconnect != null) {
                x = disconnect.getX() + disconnect.getWidth() + 4;
                y = disconnect.getY();
            }
            Button transferButton = Button.builder(Component.literal("WT"),
                    button -> client.setScreenAndShow(new TransferScreen(screen)))
                .bounds(x, y, 20, 20)
                .tooltip(Tooltip.create(Component.literal("Transfer world ownership")))
                .build();
            ((ScreenAccessor) screen).worldtransfer$addRenderableWidget(transferButton);
        });
    }

    /**
     * Finds "Save and Quit to Title" (or "Disconnect", in a joined game) by its label rather than by
     * position, since {@code PauseScreen} lays its grid out and centers it after init and doesn't
     * expose the button directly.
     */
    private static AbstractWidget findDisconnectButton(Screen screen, boolean isLocalServer) {
        Component label = CommonComponents.disconnectButtonLabel(isLocalServer);
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && label.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }

    private static IncomingTransfer offerScreenShownFor;

    /**
     * Pops the offer up once, as soon as it lands. This runs every client tick, so it must not just
     * re-show the screen unconditionally - that replaced it with a fresh instance 20 times a second
     * for as long as the offer sat unanswered, which is what was flickering. Tracking the specific
     * IncomingTransfer instance (not just its state) means the player can close or navigate away from
     * the prompt without it fighting back, while a genuinely new offer still pops up automatically.
     */
    private static void openOfferScreen(Minecraft client) {
        IncomingTransfer transfer = IncomingTransfer.current();
        if (transfer == null || transfer.state() != IncomingTransfer.State.OFFERED) {
            offerScreenShownFor = null;
            return;
        }
        if (transfer == offerScreenShownFor || client.player == null) {
            return;
        }
        offerScreenShownFor = transfer;
        client.setScreenAndShow(new IncomingTransferScreen(client.gui.screen()));
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
