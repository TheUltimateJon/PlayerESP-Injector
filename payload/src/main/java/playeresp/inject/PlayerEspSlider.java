package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

final class PlayerEspSlider extends GuiButton {
    interface Listener { void changed(double value); }
    interface Formatter { String format(double value); }
    private final double minimum, maximum, step;
    private final Listener listener;
    private final Formatter formatter;
    private double sliderValue;
    private boolean dragging;

    PlayerEspSlider(int id, int x, int y, int width, double minimum, double maximum, double step,
                    double value, Listener listener, Formatter formatter) {
        super(id, x, y, width, 20, "");
        this.minimum=minimum; this.maximum=maximum; this.step=step;
        this.listener=listener; this.formatter=formatter; sliderValue=normalize(value); updateText();
    }

    @Override protected int getHoverState(boolean mouseOver) { return 0; }
    @Override protected void mouseDragged(Minecraft mc,int mouseX,int mouseY) {
        if (!visible) return;
        if (dragging) setFromMouse(mouseX);
        mc.getTextureManager().bindTexture(buttonTextures); GlStateManager.color(1,1,1,1);
        int knob=xPosition+(int)(sliderValue*(width-8));
        drawTexturedModalRect(knob,yPosition,0,66,4,20);
        drawTexturedModalRect(knob+4,yPosition,196,66,4,20);
    }
    @Override public boolean mousePressed(Minecraft mc,int mouseX,int mouseY) {
        if (!super.mousePressed(mc,mouseX,mouseY)) return false;
        dragging=true; setFromMouse(mouseX); return true;
    }
    @Override public void mouseReleased(int mouseX,int mouseY) { dragging=false; }
    private void setFromMouse(int mouseX) {
        sliderValue=Math.max(0,Math.min(1,(mouseX-(xPosition+4))/(double)(width-8)));
        double value=value(); sliderValue=normalize(value); listener.changed(value); updateText();
    }
    private double value() {
        double raw=minimum+sliderValue*(maximum-minimum);
        double value=step<=0?raw:Math.round((raw-minimum)/step)*step+minimum;
        return Math.max(minimum,Math.min(maximum,value));
    }
    private double normalize(double value) { return maximum<=minimum?0:Math.max(0,Math.min(1,(value-minimum)/(maximum-minimum))); }
    private void updateText() { displayString=formatter.format(value()); }
}
