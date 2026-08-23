package playeresp.inject;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

public final class PlayerEspScreen extends GuiScreen {
    private final PlayerEspController controller;
    private final PlayerEspConfig config;
    private int left, right, top, columnsTop, binding;
    private boolean editingColor;
    private boolean waitForOpeningKeyRelease = true;
    private String colorText="";

    PlayerEspScreen(PlayerEspController controller,PlayerEspConfig config){this.controller=controller;this.config=config;}

    @Override public void initGui() {
        buttonList.clear(); left=width/2-210; right=width/2+10; top=height/2-146; columnsTop=top+58;
        buttonList.add(new GuiButton(0,width/2-100,top,"Menu: "+keyName(config.menuKey)));
        buttonList.add(new GuiButton(2,width/2-100,top+24,label("Player ESP",config.enabled)));
        int y=columnsTop;
        buttonList.add(new GuiButton(13,left,y,label("Render NPCs",config.renderNpcs))); y+=24;
        buttonList.add(new PlayerEspSlider(12,left,y,200,16,256,8,config.maxDistance,
            new PlayerEspSlider.Listener(){@Override public void changed(double v){config.maxDistance=(int)Math.round(v);}},
            new PlayerEspSlider.Formatter(){@Override public String format(double v){return "Render Distance: "+(int)Math.round(v);}})); y+=24;
        buttonList.add(new GuiButton(3,left,y,"Box: "+boxMode(config.boxMode))); y+=24;
        buttonList.add(new PlayerEspSlider(10,left,y,200,0.5,5,0.25,config.outlineThickness,
            new PlayerEspSlider.Listener(){@Override public void changed(double v){config.outlineThickness=(float)v;}},
            new PlayerEspSlider.Formatter(){@Override public String format(double v){return "Thickness: "+PlayerEspScreen.format((float)v);}})); y+=24;
        buttonList.add(new GuiButton(4,left,y,label("Chams",config.chams))); y+=24;
        buttonList.add(new PlayerEspSlider(11,left,y,200,0,95,5,config.chamsTransparency,
            new PlayerEspSlider.Listener(){@Override public void changed(double v){config.chamsTransparency=(int)Math.round(v);}},
            new PlayerEspSlider.Formatter(){@Override public String format(double v){return "Chams Transparency: "+(int)Math.round(v)+"%";}})); y+=24;
        buttonList.add(new GuiButton(14,left,y,"Box Color Mode: "+colorMode(config.colorMode))); y+=24;
        colorText=String.format("%06X",config.color&0xFFFFFF);
        buttonList.add(new GuiButton(9,left,y,"Box Color: #"+colorText));

        y=columnsTop;
        buttonList.add(new GuiButton(15,right,y,label("Nametag",config.nametag))); y+=24;
        buttonList.add(new PlayerEspSlider(19,right,y,200,0.5,2.0,0.05,config.nameScale,
            new PlayerEspSlider.Listener(){@Override public void changed(double v){config.nameScale=(float)v;}},
            new PlayerEspSlider.Formatter(){@Override public String format(double v){return "Name Size: "+PlayerEspScreen.format((float)v);}})); y+=24;
        buttonList.add(new GuiButton(6,right,y,"Health Bar: "+healthMode(config.healthBarPosition))); y+=24;
        buttonList.add(new GuiButton(16,right,y,label("Health Text",config.healthText))); y+=24;
        buttonList.add(new GuiButton(7,right,y,label("Distance",config.distance))); y+=24;
        buttonList.add(new GuiButton(17,right,y,label("Armor",config.armor))); y+=24;
        buttonList.add(new GuiButton(18,right,y,label("Held Item",config.heldItem))); y+=24;
        buttonList.add(new GuiButton(8,right,y,label("Target HUD",config.targetHud))); y+=24;
        buttonList.add(new GuiButton(1,right,y,"Hotkey: "+keyName(config.toggleKey)));
    }

    @Override protected void actionPerformed(GuiButton button)throws IOException {
        editingColor=false;
        switch(button.id){
            case 0:binding=1;button.displayString="Menu: ...";return;
            case 1:binding=2;button.displayString="Hotkey: ...";return;
            case 2:config.enabled=!config.enabled;break;
            case 13:config.renderNpcs=!config.renderNpcs;break;
            case 3:config.boxMode=(config.boxMode+1)%3;break;
            case 4:config.chams=!config.chams;break;
            case 14:config.colorMode=(config.colorMode+1)%2;break;
            case 15:config.nametag=!config.nametag;break;
            case 6:config.healthBarPosition=(config.healthBarPosition+1)%3;break;
            case 16:config.healthText=!config.healthText;break;
            case 7:config.distance=!config.distance;break;
            case 17:config.armor=!config.armor;break;
            case 18:config.heldItem=!config.heldItem;break;
            case 8:config.targetHud=!config.targetHud;break;
            case 9:editingColor=true;colorText=String.format("%06X",config.color&0xFFFFFF);button.displayString="Box Color: #"+colorText+"_";return;
            default:return;
        }
        controller.save();initGui();
    }

    @Override public void updateScreen(){super.updateScreen();if(waitForOpeningKeyRelease&&!Keyboard.isKeyDown(config.menuKey))waitForOpeningKeyRelease=false;}
    @Override public void drawScreen(int mouseX,int mouseY,float partialTicks){if(waitForOpeningKeyRelease&&!Keyboard.isKeyDown(config.menuKey))waitForOpeningKeyRelease=false;drawDefaultBackground();drawCenteredString(fontRendererObj,"Player ESP",width/2,top-22,0xFFFFFFFF);super.drawScreen(mouseX,mouseY,partialTicks);}
    @Override protected void mouseReleased(int x,int y,int state){controller.save();super.mouseReleased(x,y,state);}
    @Override protected void keyTyped(char c,int key)throws IOException{
        if(binding!=0){int selected=key==Keyboard.KEY_DELETE||key==Keyboard.KEY_BACK||key==Keyboard.KEY_ESCAPE?Keyboard.KEY_NONE:key;
            if(binding==1&&selected==Keyboard.KEY_NONE){binding=0;initGui();return;} if(binding==1)config.menuKey=selected;else config.toggleKey=selected;
            binding=0;controller.save();initGui();return;}
        if(editingColor){if(key==Keyboard.KEY_ESCAPE){editingColor=false;initGui();return;}if(key==Keyboard.KEY_RETURN||key==Keyboard.KEY_NUMPADENTER){applyColor();editingColor=false;controller.save();initGui();return;}
            if(key==Keyboard.KEY_BACK&&!colorText.isEmpty())colorText=colorText.substring(0,colorText.length()-1);else if(isHex(c)&&colorText.length()<6)colorText+=Character.toUpperCase(c);updateColor();return;}
        if(key==config.menuKey&&waitForOpeningKeyRelease)return;
        if(key==config.menuKey||key==Keyboard.KEY_ESCAPE){if(key==config.menuKey)controller.suppressMenuUntilRelease();controller.save();mc.displayGuiScreen(null);return;}super.keyTyped(c,key);
    }
    private void applyColor(){if(colorText.length()!=6)return;try{config.color=0xFF000000|Integer.parseInt(colorText,16);}catch(NumberFormatException ignored){}}
    private void updateColor(){for(GuiButton b:buttonList)if(b.id==9)b.displayString="Box Color: #"+colorText+"_";}
    @Override public boolean doesGuiPauseGame(){return false;}
    private static String label(String n,boolean v){return n+": "+(v?"ON":"OFF");}
    private static String boxMode(int v){return new String[]{"OFF","2D","3D"}[Math.max(0,Math.min(2,v))];}
    private static String colorMode(int v){return v==1?"CUSTOM":"NAMETAG";}
    private static String healthMode(int v){return new String[]{"OFF","TOP","SIDE"}[Math.max(0,Math.min(2,v))];}
    private static String format(float v){return v==Math.round(v)?Integer.toString(Math.round(v)):Float.toString(v);}
    private static boolean isHex(char c){return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F';}
    private static String keyName(int key){if(key==Keyboard.KEY_NONE)return "NONE";if(key==Keyboard.KEY_BACKSLASH)return "\\";String n=Keyboard.getKeyName(key);return n==null?Integer.toString(key):n;}
}
