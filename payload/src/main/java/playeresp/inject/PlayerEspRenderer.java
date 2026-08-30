package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class PlayerEspRenderer {
    private static final Pattern PLAYER_NAME=Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Comparator<EntityPlayer> FAR_TO_NEAR=new Comparator<EntityPlayer>(){@Override public int compare(EntityPlayer a,EntityPlayer b){return Double.compare(b.getDistanceSqToEntity(Minecraft.getMinecraft().thePlayer),a.getDistanceSqToEntity(Minecraft.getMinecraft().thePlayer));}};
    private static EntityPlayer lastTarget,hudTarget;
    private static long lastTargetTime,hudUpdateTime;
    private static float hudHealth;
    private PlayerEspRenderer(){}

    static void renderWorld(Minecraft mc,PlayerEspConfig config,List<EntityPlayer> players,float partial){
        PlayerEspProjectionRenderer.renderWorld(mc,config,players,partial);updateTarget(mc,players);
    }

    static boolean needsOverlay(PlayerEspConfig config){return config.boxMode==1||config.healthBarPosition!=0||config.healthText||config.distance||config.nametag||config.armor||config.heldItem||config.targetHud;}
    static boolean needsWorldStateGuard(PlayerEspConfig config){return config.chams||config.boxMode==2||config.boxMode==3;}
    static void renderOverlay(Minecraft mc,PlayerEspConfig config){PlayerEspProjectionRenderer.renderOverlay(mc,config);if(config.targetHud)drawTargetOverlay(mc,config);}

    static List<EntityPlayer> collectPlayers(Minecraft mc,PlayerEspConfig config){
        List<EntityPlayer> result=new ArrayList<EntityPlayer>(mc.theWorld==null?0:mc.theWorld.playerEntities.size());if(mc.theWorld==null||mc.thePlayer==null)return result;double maximumSq=(double)config.maxDistance*config.maxDistance;
        for(Object value:mc.theWorld.playerEntities){if(!(value instanceof EntityPlayer))continue;EntityPlayer player=(EntityPlayer)value;if(player==mc.thePlayer||!player.isEntityAlive()||mc.thePlayer.getDistanceSqToEntity(player)>maximumSq)continue;if(!config.renderNpcs&&isLikelyNpc(mc,player))continue;result.add(player);}Collections.sort(result,FAR_TO_NEAR);return result;
    }

    private static boolean isLikelyNpc(Minecraft mc,EntityPlayer player){try{
        String name=player.getName();if(name==null||!PLAYER_NAME.matcher(name).matches()||mc.getNetHandler()==null)return true;
        String display=EnumChatFormatting.getTextWithoutFormattingCodes(player.getDisplayName().getFormattedText());String upper=display==null?"":display.toUpperCase(Locale.ROOT);
        if(upper.contains("[NPC]")||upper.contains("RIGHT CLICK")||upper.contains("CLICK TO PLAY")||upper.contains("CLICK TO VIEW")||upper.contains("QUEST MASTER")||upper.contains("STORE"))return true;
        NetworkPlayerInfo info=mc.getNetHandler().getPlayerInfo(player.getUniqueID());if(info==null||info.getGameProfile()==null||info.getGameProfile().getId()==null)return true;
        String infoDisplay=info.getDisplayName()==null?ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(),info.getGameProfile().getName()):info.getDisplayName().getFormattedText();String infoUpper=plainUpper(infoDisplay);
        if(looksLikeNpc(infoUpper))return true;
        if(!player.getUniqueID().equals(info.getGameProfile().getId())||info.getGameProfile().getName()==null||!name.equalsIgnoreCase(info.getGameProfile().getName()))return true;
        if(info.getResponseTime()<1)return true;
        return false;
    }catch(Throwable ignored){return true;}}

    private static String plainUpper(String value){String plain=EnumChatFormatting.getTextWithoutFormattingCodes(value);return plain==null?"":plain.trim().toUpperCase(Locale.ROOT);}
    private static boolean looksLikeNpc(String value){return value.contains("[NPC]")||value.contains("RIGHT CLICK")||value.contains("CLICK TO PLAY")||value.contains("CLICK TO VIEW")||value.contains("CLICK FOR STATS")||value.contains("ITEM SHOP")||value.contains("TEAM UPGRADES")||value.contains("QUEST MASTER")||value.contains("SHOPKEEPER")||value.contains("QUEUE")||value.contains("PRACTICE");}
    private static void rectOutline(int x1,int y1,int x2,int y2,int color,int thickness){Gui.drawRect(x1,y1,x2,y1+thickness,color);Gui.drawRect(x1,y2-thickness,x2,y2,color);Gui.drawRect(x1,y1,x1+thickness,y2,color);Gui.drawRect(x2-thickness,y1,x2,y2,color);}

    private static void updateTarget(Minecraft mc,List<EntityPlayer> players){long now=System.currentTimeMillis();Entity hit=mc.objectMouseOver==null?null:mc.objectMouseOver.entityHit;boolean attacking=Mouse.isButtonDown(0)||mc.gameSettings.keyBindAttack.isKeyDown();if(attacking&&hit instanceof EntityPlayer&&players.contains(hit)){lastTarget=(EntityPlayer)hit;lastTargetTime=now;}if(lastTarget!=null&&(!lastTarget.isEntityAlive()||!players.contains(lastTarget)||now-lastTargetTime>1500L)){lastTarget=null;hudTarget=null;}}
    private static void drawTargetOverlay(Minecraft mc,PlayerEspConfig config){int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();try{PlayerEspProjectionRenderer.setupOverlayWithoutClearingDepth(mc);GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);GlStateManager.enableTexture2D();GlStateManager.disableDepth();GlStateManager.color(1,1,1,1);targetHud(mc,config);}finally{GlStateManager.enableDepth();GlStateManager.disableBlend();GlStateManager.color(1,1,1,1);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(oldMode);}}
    private static void targetHud(Minecraft mc,PlayerEspConfig config){if(lastTarget==null){hudTarget=null;return;}float actual=Math.max(0,lastTarget.getHealth()+lastTarget.getAbsorptionAmount()),maximum=Math.max(1,lastTarget.getMaxHealth());long now=System.currentTimeMillis();if(hudTarget!=lastTarget){hudTarget=lastTarget;hudHealth=actual;hudUpdateTime=now;}long elapsed=Math.max(0,Math.min(100,now-hudUpdateTime));hudUpdateTime=now;float smoothing=1F-(float)Math.exp(-elapsed/85D);hudHealth+=(actual-hudHealth)*smoothing;float ratio=Math.max(0,Math.min(1,hudHealth/maximum));ScaledResolution sr=new ScaledResolution(mc);int x=sr.getScaledWidth()/2+36,y=sr.getScaledHeight()/2+20,w=158,h=48,color=renderColor(lastTarget,config);Gui.drawRect(x,y,x+w,y+h,0xE0080A0E);rectOutline(x,y,x+w,y+h,color,1);int textX=x+42;GlStateManager.enableTexture2D();if(lastTarget instanceof AbstractClientPlayer){mc.getTextureManager().bindTexture(((AbstractClientPlayer)lastTarget).getLocationSkin());GlStateManager.color(1,1,1,1);Gui.drawScaledCustomSizeModalRect(x+5,y+5,8F,8F,8,8,32,32,64F,64F);Gui.drawScaledCustomSizeModalRect(x+5,y+5,40F,8F,8,8,32,32,64F,64F);}String playerName=mc.fontRendererObj.trimStringToWidth(lastTarget.getName(),w-49);mc.fontRendererObj.drawStringWithShadow(playerName,textX,y+6,0xFFFFFFFF);String hp=oneDecimal(actual)+" / "+oneDecimal(maximum)+" HP";mc.fontRendererObj.drawStringWithShadow(hp,textX,y+18,0xFFE8E8E8);Gui.drawRect(textX,y+33,x+w-6,y+40,0xE0202020);Gui.drawRect(textX+1,y+34,textX+1+Math.round((w-50)*ratio),y+39,healthColor(ratio));}

    static int renderColor(EntityPlayer player,PlayerEspConfig config){if(config.colorMode==1)return config.color;int last=-1;if(player.getTeam() instanceof ScorePlayerTeam){String prefix=((ScorePlayerTeam)player.getTeam()).getColorPrefix();last=lastColor(prefix,prefix==null?0:prefix.length());}if(last==-1){String text=player.getDisplayName().getFormattedText();int nameAt=text.indexOf(player.getName());last=lastColor(text,nameAt<0?text.length():nameAt);}return last==-1?0xFFFFFFFF:0xFF000000|last;}
    private static int lastColor(String text,int limit){if(text==null)return-1;int last=-1;for(int i=0;i+1<Math.min(limit,text.length());i++)if(text.charAt(i)=='\u00a7'){int value=colorCode(text.charAt(i+1));if(value!=-1)last=value;}return last;}
    private static int colorCode(char code){switch(Character.toLowerCase(code)){case'0':return 0x000000;case'1':return 0x0000AA;case'2':return 0x00AA00;case'3':return 0x00AAAA;case'4':return 0xAA0000;case'5':return 0xAA00AA;case'6':return 0xFFAA00;case'7':return 0xAAAAAA;case'8':return 0x555555;case'9':return 0x5555FF;case'a':return 0x55FF55;case'b':return 0x55FFFF;case'c':return 0xFF5555;case'd':return 0xFF55FF;case'e':return 0xFFFF55;case'f':return 0xFFFFFF;default:return-1;}}
    private static int healthColor(float ratio){return 0xFF000000|((int)(255*(1-ratio))<<16)|((int)(255*ratio)<<8);}
    private static String oneDecimal(float value){int rounded=Math.round(value*10F);return Integer.toString(rounded/10)+'.'+Math.abs(rounded%10);}
}
