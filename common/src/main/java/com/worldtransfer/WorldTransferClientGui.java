package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared client GUI behavior; loader modules only provide event and widget hooks. */
public final class WorldTransferClientGui {
    private static IncomingTransfer offerScreenShownFor;
    private static Path receiptShownFor;

    private WorldTransferClientGui() {
    }

    public static void initialize() {
        TransferClientPlatform.current().registerClientTick(client -> {
            IncomingTransfer.tick();
            openOfferScreen(client);
            showHandoverReceipt(client);
        });
        TransferClientPlatform.current().registerPauseMenu(WorldTransferClientGui::addPauseButton);
    }

    private static void addPauseButton(Screen screen) {
        if (!(screen instanceof PauseScreen pauseScreen) || !pauseScreen.showsPauseMenu()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int x = screen.width / 2 + 106;
        int y = screen.height / 4 + 216;
        AbstractWidget disconnect = findDisconnectButton(pauseScreen, client.isLocalServer());
        if (disconnect != null) {
            x = disconnect.getX() + disconnect.getWidth() + 4;
            y = disconnect.getY();
        }
        Button transferButton = Button.builder(Component.literal("WT"),
                ignored -> TransferClientPlatform.current().openScreen(client, new TransferScreen(screen)))
            .bounds(x, y, 20, 20)
            .tooltip(Tooltip.create(Component.literal("World Tranfer")))
            .build();
        TransferClientPlatform.current().addPauseButton(screen, transferButton);
    }

    private static AbstractWidget findDisconnectButton(Screen screen, boolean isLocalServer) {
        Component label = CommonComponents.disconnectButtonLabel(isLocalServer);
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && label.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }

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
        TransferClientPlatform.current().openScreen(client, new IncomingTransferScreen(client.gui.screen()));
    }

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
        } catch (Exception ignored) {
        }
    }
}
