package com.worldtransfer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

/** A player head, sized by the caller so it can sit next to a 20px button row. */
public class SkinFace extends AbstractWidget {
    private final PlayerSkin skin;
    private final int size;

    public SkinFace(int x, int y, int size, PlayerSkin skin) {
        super(x, y, size, size, Component.empty());
        this.skin = skin;
        this.size = size;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(
            graphics, skin, getX(), getY(), size);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.empty());
    }
}
