package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiSpectator;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.IChatComponent;

/** Delegates the normal client HUD, then renders PlayerESP in a real post-HUD phase. */
final class PlayerEspHudBridge extends GuiIngame {
    private final GuiIngame delegate;
    private volatile PlayerEspController controller;

    PlayerEspHudBridge(Minecraft mc, GuiIngame delegate, PlayerEspController controller) {
        super(mc);
        this.delegate = delegate;
        this.controller = controller;
    }

    void setController(PlayerEspController controller) { this.controller = controller; }

    @Override public void renderGameOverlay(float partialTicks) {
        delegate.renderGameOverlay(partialTicks);
        PlayerEspController value = controller;
        if (value != null) value.onHudRender(partialTicks);
    }

    @Override public void setDefaultTitlesTimes() { delegate.setDefaultTitlesTimes(); }
    @Override public void renderHorseJumpBar(ScaledResolution resolution, int x) { delegate.renderHorseJumpBar(resolution, x); }
    @Override public void renderExpBar(ScaledResolution resolution, int x) { delegate.renderExpBar(resolution, x); }
    @Override public void renderSelectedItem(ScaledResolution resolution) { delegate.renderSelectedItem(resolution); }
    @Override public void renderDemo(ScaledResolution resolution) { delegate.renderDemo(resolution); }
    @Override public void renderStreamIndicator(ScaledResolution resolution) { delegate.renderStreamIndicator(resolution); }
    @Override public void updateTick() { delegate.updateTick(); }
    @Override public void setRecordPlayingMessage(String name) { delegate.setRecordPlayingMessage(name); }
    @Override public void setRecordPlaying(String message, boolean playing) { delegate.setRecordPlaying(message, playing); }
    @Override public void displayTitle(String title, String subtitle, int fadeIn, int displayTime, int fadeOut) { delegate.displayTitle(title, subtitle, fadeIn, displayTime, fadeOut); }
    @Override public void setRecordPlaying(IChatComponent component, boolean playing) { delegate.setRecordPlaying(component, playing); }
    @Override public GuiNewChat getChatGUI() { return delegate.getChatGUI(); }
    @Override public int getUpdateCounter() { return delegate.getUpdateCounter(); }
    @Override public FontRenderer getFontRenderer() { return delegate.getFontRenderer(); }
    @Override public GuiSpectator getSpectatorGui() { return delegate.getSpectatorGui(); }
    @Override public GuiPlayerTabOverlay getTabList() { return delegate.getTabList(); }
    @Override public void resetPlayersOverlayFooterHeader() { delegate.resetPlayersOverlayFooterHeader(); }
}
