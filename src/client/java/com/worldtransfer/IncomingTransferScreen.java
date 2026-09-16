package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows an incoming world hand-over: who is offering, how big it is, whether the receiving game can
 * open it, and then the progress of the transfer itself.
 *
 * <p>Labels are drawn as disabled buttons, the same trick the other screens in this mod use, so the
 * screen does not have to touch the rendering API.</p>
 */
public class IncomingTransferScreen extends Screen {
    private final Screen parent;
    private IncomingTransfer.State lastState;
    private String lastStatus = "";

    public IncomingTransferScreen(Screen parent) {
        super(Component.literal("Incoming world transfer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        IncomingTransfer transfer = IncomingTransfer.current();
        if (transfer == null) {
            label("No transfer is in progress.", this.height / 2 - 20);
            addRenderableWidget(closeButton());
            return;
        }

        lastState = transfer.state();
        lastStatus = transfer.status();

        int y = this.height / 4;
        label(transfer.index().get("host", "Someone") + " wants to hand you \""
            + transfer.index().get("world", "a world") + "\"", y);
        y += 24;
        label("Minecraft " + transfer.index().get("game", "?") + " - "
            + TransferData.humanBytes(transfer.index().totalBytes()) + " in total", y);
        y += 24;

        String problem = transfer.versionProblem();
        String warning = transfer.versionWarning();
        if (problem != null) {
            label(problem, y);
            y += 24;
        } else if (warning != null) {
            label(warning, y);
            y += 24;
        }

        if (!transfer.status().isEmpty()) {
            label(transfer.status(), y);
            y += 28;
        }

        if (transfer.state() == IncomingTransfer.State.OFFERED) {
            Button accept = Button.builder(Component.literal("Accept and download"), button -> {
                    transfer.accept();
                    refresh();
                })
                .bounds(this.width / 2 - 155, y, 150, 20)
                .build();
            accept.active = problem == null;
            addRenderableWidget(accept);

            addRenderableWidget(Button.builder(Component.literal("Decline"), button -> {
                    transfer.decline();
                    onClose();
                })
                .bounds(this.width / 2 + 5, y, 150, 20)
                .build());
        } else if (transfer.state() == IncomingTransfer.State.DONE) {
            label("Saved as: " + transfer.resultFolder(), y);
        }

        addRenderableWidget(closeButton());
    }

    @Override
    public void tick() {
        IncomingTransfer transfer = IncomingTransfer.current();
        IncomingTransfer.State state = transfer == null ? null : transfer.state();
        String status = transfer == null ? "" : transfer.status();
        if (state != lastState || !status.equals(lastStatus)) {
            refresh();
        }
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    private void label(String text, int y) {
        Button label = Button.builder(Component.literal(text), button -> { })
            .bounds(this.width / 2 - 160, y, 320, 20)
            .build();
        label.active = false;
        addRenderableWidget(label);
    }

    private Button closeButton() {
        return Button.builder(Component.literal("Close"), button -> onClose())
            .bounds(this.width / 2 - 100, this.height - 35, 200, 20)
            .build();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
