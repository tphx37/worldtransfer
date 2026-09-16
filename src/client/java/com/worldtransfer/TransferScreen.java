package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * Pick who becomes the next host, and how the world gets to them:
 * straight over the LAN connection, or as a ZIP to send by hand.
 */
public class TransferScreen extends Screen {
    private final Screen parent;
    private final TransferSettings settings;

    public TransferScreen(Screen parent) {
        this(parent, new TransferSettings());
    }

    public TransferScreen(Screen parent, TransferSettings settings) {
        super(Component.literal("Transfer world ownership"));
        this.parent = parent;
        this.settings = settings;
    }

    @Override
    protected void init() {
        int y = 45;
        Minecraft client = Minecraft.getInstance();
        boolean host = client.isLocalServer();
        boolean foundPlayer = false;

        if (client.getConnection() != null) {
            for (PlayerInfo player : client.getConnection().getOnlinePlayers()) {
                if (client.player != null && player.getProfile().id().equals(client.player.getUUID())) {
                    continue;
                }
                String playerName = player.getProfile().name();
                foundPlayer = true;

                Button sendButton = Button.builder(Component.literal("Send to " + playerName),
                        button -> run("send " + playerName + " " + flags()))
                    .bounds(this.width / 2 - 70, y + 4, 110, 20)
                    .build();
                sendButton.active = host;
                addRenderableWidget(sendButton);

                Button zipButton = Button.builder(Component.literal("ZIP for " + playerName),
                        button -> run("prepare " + playerName + " "
                            + (settings.saveToDesktop ? "desktop" : "minecraft") + " " + flags()))
                    .bounds(this.width / 2 + 46, y + 4, 110, 20)
                    .build();
                zipButton.active = host;
                addRenderableWidget(zipButton);

                addRenderableOnly(new SkinFace(this.width / 2 - 128, y - 10, player.getSkin()));
                y += 46;
            }
        }

        if (!foundPlayer) {
            Button noPlayerButton = Button.builder(Component.literal("No other players are online"), button -> { })
                .bounds(this.width / 2 - 120, y, 240, 20)
                .build();
            noPlayerButton.active = false;
            addRenderableWidget(noPlayerButton);
            y += 30;
        }

        addRenderableWidget(Button.builder(Component.literal("Advanced settings"),
                button -> Minecraft.getInstance().setScreenAndShow(new TransferSettingsScreen(this, settings)))
            .bounds(this.width / 2 - 120, y + 8, 240, 20)
            .build());

        Button historyButton = Button.builder(Component.literal("Ownership history"),
                button -> Minecraft.getInstance().setScreenAndShow(new TransferHistoryScreen(this)))
            .bounds(this.width / 2 - 120, y + 32, 240, 20)
            .build();
        historyButton.active = Minecraft.getInstance().getSingleplayerServer() != null;
        addRenderableWidget(historyButton);

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(this.width / 2 - 100, this.height - 35, 200, 20)
            .build());
    }

    private String flags() {
        return settings.includePlayerData + " " + settings.includeAdvancements + " "
            + settings.includeEntities + " " + settings.includeWorldData;
    }

    private void run(String arguments) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand("worldtransfer " + arguments);
        }
        client.setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
