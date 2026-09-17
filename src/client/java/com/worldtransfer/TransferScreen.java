package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick who becomes the next host, and how the world gets to them: straight over the LAN connection,
 * or as a ZIP to send by hand.
 *
 * <p>The player list is windowed rather than laid out in full, so a busy server does not push the
 * buttons off the bottom of the screen. The mouse wheel and the two arrow buttons move the window.</p>
 */
public class TransferScreen extends Screen {
    private static final int HEAD_SIZE = 20;
    private static final int ROW_HEIGHT = 26;
    private static final int LIST_TOP = 40;

    private final Screen parent;
    private final TransferSettings settings;
    private final List<PlayerInfo> players = new ArrayList<>();
    private int scroll;
    private int visibleRows = 1;

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
        Minecraft client = Minecraft.getInstance();
        boolean host = client.isLocalServer();

        players.clear();
        if (client.getConnection() != null) {
            for (PlayerInfo player : client.getConnection().getOnlinePlayers()) {
                if (client.player != null && player.getProfile().id().equals(client.player.getUUID())) {
                    continue;
                }
                players.add(player);
            }
        }

        // Leave room for the four buttons at the bottom before deciding how many rows fit.
        int listHeight = Math.max(ROW_HEIGHT, this.height - LIST_TOP - 135);
        visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, players.size() - visibleRows)));

        int y = LIST_TOP;
        if (players.isEmpty()) {
            label("No other players are online", y, 240);
            y += ROW_HEIGHT;
        } else {
            int end = Math.min(players.size(), scroll + visibleRows);
            for (int i = scroll; i < end; i++) {
                PlayerInfo player = players.get(i);
                String playerName = player.getProfile().name();

                addRenderableOnly(new SkinFace(this.width / 2 - 150, y, HEAD_SIZE, player.getSkin()));

                Button sendButton = Button.builder(Component.literal("Send to " + playerName),
                        button -> run("send " + playerName + " " + settings.asCommandArguments()))
                    .bounds(this.width / 2 - 124, y, 124, 20)
                    .build();
                sendButton.active = host;
                addRenderableWidget(sendButton);

                Button zipButton = Button.builder(Component.literal("ZIP"),
                        button -> run("prepare " + playerName + " " + settings.asCommandArguments()))
                    .bounds(this.width / 2 + 6, y, 60, 20)
                    .build();
                zipButton.active = host;
                addRenderableWidget(zipButton);

                y += ROW_HEIGHT;
            }

            if (players.size() > visibleRows) {
                Button up = Button.builder(Component.literal("\u25B2"), button -> scrollBy(-1))
                    .bounds(this.width / 2 + 74, LIST_TOP, 20, 20)
                    .build();
                up.active = scroll > 0;
                addRenderableWidget(up);

                Button down = Button.builder(Component.literal("\u25BC"), button -> scrollBy(1))
                    .bounds(this.width / 2 + 74, LIST_TOP + (visibleRows - 1) * ROW_HEIGHT, 20, 20)
                    .build();
                down.active = scroll + visibleRows < players.size();
                addRenderableWidget(down);

                label((scroll + 1) + "-" + Math.min(players.size(), scroll + visibleRows)
                    + " of " + players.size(), y, 120);
                y += ROW_HEIGHT;
            }
        }

        int bottom = this.height - 125;
        addRenderableWidget(Button.builder(Component.literal("Advanced settings"),
                button -> Minecraft.getInstance().setScreenAndShow(new TransferSettingsScreen(this, settings)))
            .bounds(this.width / 2 - 120, bottom, 240, 20)
            .build());

        Button historyButton = Button.builder(Component.literal("Ownership history"),
                button -> Minecraft.getInstance().setScreenAndShow(new TransferHistoryScreen(this)))
            .bounds(this.width / 2 - 120, bottom + 24, 240, 20)
            .build();
        historyButton.active = Minecraft.getInstance().getSingleplayerServer() != null;
        addRenderableWidget(historyButton);

        // Pulled in here rather than sitting as its own pause-menu button: this is where a recipient
        // now checks on (or reopens) an offer instead of it fighting for space up top.
        boolean hasIncoming = IncomingTransfer.current() != null;
        Button incomingButton = Button.builder(Component.literal(hasIncoming
                    ? "Incoming world transfer (" + IncomingTransfer.current().state() + ")"
                    : "No incoming world transfer"),
                button -> Minecraft.getInstance().setScreenAndShow(new IncomingTransferScreen(this)))
            .bounds(this.width / 2 - 120, bottom + 48, 240, 20)
            .build();
        incomingButton.active = hasIncoming;
        addRenderableWidget(incomingButton);

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(this.width / 2 - 100, this.height - 35, 200, 20)
            .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (players.size() > visibleRows && scrollY != 0.0) {
            scrollBy(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void scrollBy(int rows) {
        int max = Math.max(0, players.size() - visibleRows);
        int updated = Math.max(0, Math.min(max, scroll + rows));
        if (updated != scroll) {
            scroll = updated;
            clearWidgets();
            init();
        }
    }

    private void label(String text, int y, int width) {
        Button label = Button.builder(Component.literal(text), button -> { })
            .bounds(this.width / 2 - width / 2, y, width, 20)
            .build();
        label.active = false;
        addRenderableWidget(label);
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
