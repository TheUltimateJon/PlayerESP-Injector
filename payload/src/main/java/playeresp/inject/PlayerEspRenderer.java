package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class PlayerEspRenderer {
    private static final Frustum FRUSTUM=new Frustum();
    private static final Pattern PLAYER_NAME=Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Comparator<EntityPlayer> FAR_TO_NEAR=new Comparator<EntityPlayer>(){@Override public int compare(EntityPlayer a,EntityPlayer b){return Double.compare(b.getDistanceSqToEntity(Minecraft.getMinecraft().thePlayer),a.getDistanceSqToEntity(Minecraft.getMinecraft().thePlayer));}};
    private static EntityPlayer lastTarget,hudTarget;
    private static long lastTargetTime,hudUpdateTime;
    private static float hudHealth;
    private PlayerEspRenderer(){}

    static void renderWorld(Minecraft mc,PlayerEspConfig config,List<EntityPlayer> players,float partial){
        RenderManager rm=mc.getRenderManager();Entity view=mc.getRenderViewEntity();
        if(view!=null)FRUSTUM.setPosition(view.posX,view.posY,view.posZ);
        for(EntityPlayer player:players){
            if(view!=null&&!player.ignoreFrustumCheck&&!FRUSTUM.isBoundingBoxInFrustum(player.getEntityBoundingBox().expand(0.1D,0.1D,0.1D)))continue;
            int color=renderColor(player,config);AxisAlignedBB worldBox=interpolatedBox(player,partial).expand(0.04D,0.03D,0.04D);
            if(config.chams)drawFilled(rm,worldBox,color,config.chamsTransparency);
            if(config.boxMode==2)drawOutline(rm,worldBox,color,config.outlineThickness);
            if(config.boxMode==1||config.distance||config.healthBarPosition!=0)drawPlayerFrame(mc,rm,player,partial,color,config);
            if(config.nametag||config.armor||config.heldItem)drawPlayerTag(mc,rm,player,partial,color,config);
        }
        updateTarget(mc,players);
    }

    static void renderOverlay(Minecraft mc,PlayerEspConfig config){if(config.targetHud)drawTargetOverlay(mc,config);}

    static List<EntityPlayer> collectPlayers(Minecraft mc,PlayerEspConfig config){
        List<EntityPlayer> result=new ArrayList<EntityPlayer>();if(mc.theWorld==null||mc.thePlayer==null)return result;double maximumSq=(double)config.maxDistance*config.maxDistance;
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
        AxisAlignedBB nearby=player.getEntityBoundingBox().expand(1.4D,3.0D,1.4D);for(Object value:mc.theWorld.getEntitiesWithinAABB(EntityArmorStand.class,nearby)){EntityArmorStand stand=(EntityArmorStand)value;String label=plainUpper(stand.getDisplayName().getFormattedText()+" "+stand.getCustomNameTag());if(looksLikeNpc(label))return true;}return false;
    }catch(Throwable ignored){return true;}}

    private static String plainUpper(String value){String plain=EnumChatFormatting.getTextWithoutFormattingCodes(value);return plain==null?"":plain.trim().toUpperCase(Locale.ROOT);}
    private static boolean looksLikeNpc(String value){return value.contains("[NPC]")||value.contains("RIGHT CLICK")||value.contains("CLICK TO PLAY")||value.contains("CLICK TO VIEW")||value.contains("CLICK FOR STATS")||value.contains("ITEM SHOP")||value.contains("TEAM UPGRADES")||value.contains("QUEST MASTER")||value.contains("SHOPKEEPER")||value.contains("QUEUE")||value.contains("PRACTICE");}
    private static AxisAlignedBB interpolatedBox(EntityPlayer player,float partial){double x=interpolate(player.lastTickPosX,player.posX,partial),y=interpolate(player.lastTickPosY,player.posY,partial),z=interpolate(player.lastTickPosZ,player.posZ,partial);return player.getEntityBoundingBox().offset(x-player.posX,y-player.posY,z-player.posZ);}

    private static void beginThroughWalls(){GlStateManager.pushMatrix();GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);GlStateManager.disableTexture2D();GlStateManager.disableDepth();GlStateManager.disableFog();GlStateManager.disableLighting();GlStateManager.depthMask(false);}
    private static void endThroughWalls(){GlStateManager.depthMask(true);GlStateManager.enableDepth();GlStateManager.enableLighting();GlStateManager.enableFog();GlStateManager.enableTexture2D();GlStateManager.disableBlend();GlStateManager.color(1,1,1,1);GlStateManager.popMatrix();}

    private static void drawOutline(RenderManager rm,AxisAlignedBB world,int color,float width){AxisAlignedBB box=world.offset(-rm.viewerPosX,-rm.viewerPosY,-rm.viewerPosZ);beginThroughWalls();try{GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glHint(GL11.GL_LINE_SMOOTH_HINT,GL11.GL_NICEST);GL11.glLineWidth(Math.max(0.5F,width));RenderGlobal.drawOutlinedBoundingBox(box,color>>16&255,color>>8&255,color&255,255);}finally{GL11.glLineWidth(1F);GL11.glDisable(GL11.GL_LINE_SMOOTH);endThroughWalls();}}

    private static void drawFilled(RenderManager rm,AxisAlignedBB world,int color,int transparency){AxisAlignedBB q=world.offset(-rm.viewerPosX,-rm.viewerPosY,-rm.viewerPosZ);int r=color>>16&255,g=color>>8&255,b=color&255,a=Math.round((100-Math.max(0,Math.min(95,transparency)))*2.55F);beginThroughWalls();try{Tessellator t=Tessellator.getInstance();WorldRenderer w=t.getWorldRenderer();w.begin(GL11.GL_QUADS,DefaultVertexFormats.POSITION_COLOR);double[][] v={{q.minX,q.minY,q.minZ},{q.minX,q.minY,q.maxZ},{q.maxX,q.minY,q.maxZ},{q.maxX,q.minY,q.minZ},{q.minX,q.maxY,q.minZ},{q.maxX,q.maxY,q.minZ},{q.maxX,q.maxY,q.maxZ},{q.minX,q.maxY,q.maxZ},{q.minX,q.minY,q.minZ},{q.minX,q.maxY,q.minZ},{q.minX,q.maxY,q.maxZ},{q.minX,q.minY,q.maxZ},{q.maxX,q.minY,q.minZ},{q.maxX,q.minY,q.maxZ},{q.maxX,q.maxY,q.maxZ},{q.maxX,q.maxY,q.minZ},{q.minX,q.minY,q.minZ},{q.maxX,q.minY,q.minZ},{q.maxX,q.maxY,q.minZ},{q.minX,q.maxY,q.minZ},{q.minX,q.minY,q.maxZ},{q.minX,q.maxY,q.maxZ},{q.maxX,q.maxY,q.maxZ},{q.maxX,q.minY,q.maxZ}};for(double[] p:v)w.pos(p[0],p[1],p[2]).color(r,g,b,a).endVertex();t.draw();}finally{endThroughWalls();}}

    private static void billboard(RenderManager rm,EntityPlayer player,float partial,double yOffset,float scale){double x=interpolate(player.lastTickPosX,player.posX,partial)-rm.viewerPosX,y=interpolate(player.lastTickPosY,player.posY,partial)-rm.viewerPosY,z=interpolate(player.lastTickPosZ,player.posZ,partial)-rm.viewerPosZ;GlStateManager.translate(x,y+yOffset,z);GlStateManager.rotate(-rm.playerViewY,0,1,0);GlStateManager.rotate(rm.playerViewX,1,0,0);GlStateManager.scale(-scale,-scale,scale);}

    private static void drawPlayerFrame(Minecraft mc,RenderManager rm,EntityPlayer player,float partial,int color,PlayerEspConfig config){float scale=0.1F,half=(float)(23.3D*player.width/2D),top=-12F,bottom=12F,line=Math.max(0.25F,config.outlineThickness*0.27F);float health=Math.max(0,player.getHealth()+player.getAbsorptionAmount()),maximum=Math.max(1,player.getMaxHealth()),ratio=Math.max(0,Math.min(1,health/maximum));beginThroughWalls();try{double x=interpolate(player.lastTickPosX,player.posX,partial)-rm.viewerPosX,y=interpolate(player.lastTickPosY,player.posY,partial)-rm.viewerPosY,z=interpolate(player.lastTickPosZ,player.posZ,partial)-rm.viewerPosZ;GlStateManager.translate(x,y+player.height/2D,z);GlStateManager.rotate(-rm.playerViewY,0,1,0);GlStateManager.scale(-scale,-scale,scale);
            if(config.boxMode==1){GlStateManager.color((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,1F);quad(-half,top,half,top+line);quad(-half,bottom-line,half,bottom);quad(-half,top,-half+line,bottom);quad(half-line,top,half,bottom);GlStateManager.color(1,1,1,1);}
            if(config.healthBarPosition==1){float y1=top-1.7F;drawColoredQuad(-half,y1,half,y1+0.8F,0xDD202020);drawColoredQuad(-half,y1,-half+half*2F*ratio,y1+0.8F,healthColor(ratio));}
            else if(config.healthBarPosition==2){float x1=-half-1.8F;drawColoredQuad(x1,top,x1+0.8F,bottom,0xDD202020);drawColoredQuad(x1,bottom-(bottom-top)*ratio,x1+0.8F,bottom,healthColor(ratio));}
            if(config.distance||config.healthText){GlStateManager.enableTexture2D();GlStateManager.pushMatrix();GlStateManager.translate(half+2.2F,-2.5F,0);GlStateManager.scale(0.2666667F,0.2666667F,0.2666667F);int textY=0;if(config.healthText){String hp=formatHealth(health)+" HP";mc.fontRendererObj.drawStringWithShadow(hp,0,textY,0xFFFFFFFF);textY+=10;}if(config.distance)mc.fontRendererObj.drawStringWithShadow(String.format(Locale.ROOT,"%.1fm",mc.thePlayer.getDistanceToEntity(player)),0,textY,0xFFDDDDDD);GlStateManager.popMatrix();GlStateManager.disableTexture2D();}
        }finally{endThroughWalls();}}
    private static void quad(float x1,float y1,float x2,float y2){GL11.glBegin(GL11.GL_QUADS);GL11.glVertex3f(x1,y1,0);GL11.glVertex3f(x2,y1,0);GL11.glVertex3f(x2,y2,0);GL11.glVertex3f(x1,y2,0);GL11.glEnd();}
    private static void drawColoredQuad(float x1,float y1,float x2,float y2,int color){GlStateManager.color((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,(color>>>24&255)/255F);quad(x1,y1,x2,y2);GlStateManager.color(1,1,1,1);}
    private static void rectOutline(int x1,int y1,int x2,int y2,int color,int thickness){Gui.drawRect(x1,y1,x2,y1+thickness,color);Gui.drawRect(x1,y2-thickness,x2,y2,color);Gui.drawRect(x1,y1,x1+thickness,y2,color);Gui.drawRect(x2-thickness,y1,x2,y2,color);}

    private static void drawPlayerTag(Minecraft mc,RenderManager rm,EntityPlayer player,float partial,int color,PlayerEspConfig config){
        String text=config.nametag?(config.colorMode==1?player.getName():player.getDisplayName().getFormattedText()):"";
        float scale=0.02666667F*Math.max(0.5F,Math.min(2F,config.nameScale));beginThroughWalls();try{billboard(rm,player,partial,player.height+0.55D,scale);GlStateManager.enableTexture2D();
            int textWidth=text.isEmpty()?0:mc.fontRendererObj.getStringWidth(text),half=Math.max(14,textWidth/2+3),top=-2;
            if(!text.isEmpty()){Gui.drawRect(-half,top,half,11,0xE8000000);mc.fontRendererObj.drawStringWithShadow(text,-textWidth/2,1,config.colorMode==1?color:0xFFFFFFFF);}
            if(config.armor||config.heldItem)drawEquipment(mc,player,config,top-20);
        }finally{endThroughWalls();}
    }

    private static void drawEquipment(Minecraft mc,EntityPlayer player,PlayerEspConfig config,int y){List<ItemStack> stacks=new ArrayList<ItemStack>();if(config.armor)for(int slot=3;slot>=0;slot--){ItemStack stack=player.getCurrentArmor(slot);if(stack!=null)stacks.add(stack);}if(config.heldItem&&player.getHeldItem()!=null)stacks.add(player.getHeldItem());if(stacks.isEmpty())return;GlStateManager.pushMatrix();try{int x=-(stacks.size()*18)/2;RenderItem renderer=mc.getRenderItem();GlStateManager.enableTexture2D();GlStateManager.enableDepth();RenderHelper.enableGUIStandardItemLighting();for(ItemStack stack:stacks){renderer.renderItemAndEffectIntoGUI(stack,x,y);renderer.renderItemOverlays(mc.fontRendererObj,stack,x,y);x+=18;}}finally{RenderHelper.disableStandardItemLighting();GlStateManager.disableDepth();GlStateManager.enableBlend();GlStateManager.color(1,1,1,1);GlStateManager.popMatrix();}}

    private static void updateTarget(Minecraft mc,List<EntityPlayer> players){Entity hit=mc.objectMouseOver==null?null:mc.objectMouseOver.entityHit;if(hit instanceof EntityPlayer&&players.contains(hit)){lastTarget=(EntityPlayer)hit;lastTargetTime=System.currentTimeMillis();}if(lastTarget!=null&&(!lastTarget.isEntityAlive()||!players.contains(lastTarget)||System.currentTimeMillis()-lastTargetTime>3000))lastTarget=null;}
    private static void drawTargetOverlay(Minecraft mc,PlayerEspConfig config){int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();try{mc.entityRenderer.setupOverlayRendering();GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);GlStateManager.enableTexture2D();GlStateManager.disableDepth();GlStateManager.color(1,1,1,1);targetHud(mc,config);}finally{GlStateManager.enableDepth();GlStateManager.disableBlend();GlStateManager.color(1,1,1,1);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(oldMode);}}
    private static void targetHud(Minecraft mc,PlayerEspConfig config){if(lastTarget==null){hudTarget=null;return;}float actual=Math.max(0,lastTarget.getHealth()+lastTarget.getAbsorptionAmount()),maximum=Math.max(1,lastTarget.getMaxHealth());long now=System.currentTimeMillis();if(hudTarget!=lastTarget){hudTarget=lastTarget;hudHealth=actual;hudUpdateTime=now;}long elapsed=Math.max(0,Math.min(100,now-hudUpdateTime));hudUpdateTime=now;float smoothing=1F-(float)Math.exp(-elapsed/85D);hudHealth+=(actual-hudHealth)*smoothing;float ratio=Math.max(0,Math.min(1,hudHealth/maximum));ScaledResolution sr=new ScaledResolution(mc);int x=sr.getScaledWidth()/2+36,y=sr.getScaledHeight()/2+20,w=158,h=48,color=renderColor(lastTarget,config);Gui.drawRect(x,y,x+w,y+h,0xE0080A0E);rectOutline(x,y,x+w,y+h,color,1);int textX=x+42;GlStateManager.enableTexture2D();if(lastTarget instanceof AbstractClientPlayer){mc.getTextureManager().bindTexture(((AbstractClientPlayer)lastTarget).getLocationSkin());GlStateManager.color(1,1,1,1);Gui.drawScaledCustomSizeModalRect(x+5,y+5,8F,8F,8,8,32,32,64F,64F);Gui.drawScaledCustomSizeModalRect(x+5,y+5,40F,8F,8,8,32,32,64F,64F);}String playerName=mc.fontRendererObj.trimStringToWidth(lastTarget.getName(),w-49);mc.fontRendererObj.drawStringWithShadow(playerName,textX,y+6,0xFFFFFFFF);String hp=String.format(Locale.ROOT,"%.1f / %.1f HP",actual,maximum);mc.fontRendererObj.drawStringWithShadow(hp,textX,y+18,0xFFE8E8E8);Gui.drawRect(textX,y+33,x+w-6,y+40,0xE0202020);Gui.drawRect(textX+1,y+34,textX+1+Math.round((w-50)*ratio),y+39,healthColor(ratio));}

    private static int renderColor(EntityPlayer player,PlayerEspConfig config){if(config.colorMode==1)return config.color;int last=-1;if(player.getTeam() instanceof ScorePlayerTeam){String prefix=((ScorePlayerTeam)player.getTeam()).getColorPrefix();last=lastColor(prefix,prefix==null?0:prefix.length());}if(last==-1){String text=player.getDisplayName().getFormattedText();int nameAt=text.indexOf(player.getName());last=lastColor(text,nameAt<0?text.length():nameAt);}return last==-1?0xFFFFFFFF:0xFF000000|last;}
    private static int lastColor(String text,int limit){if(text==null)return-1;int last=-1;for(int i=0;i+1<Math.min(limit,text.length());i++)if(text.charAt(i)=='\u00a7'){int value=colorCode(text.charAt(i+1));if(value!=-1)last=value;}return last;}
    private static int colorCode(char code){switch(Character.toLowerCase(code)){case'0':return 0x000000;case'1':return 0x0000AA;case'2':return 0x00AA00;case'3':return 0x00AAAA;case'4':return 0xAA0000;case'5':return 0xAA00AA;case'6':return 0xFFAA00;case'7':return 0xAAAAAA;case'8':return 0x555555;case'9':return 0x5555FF;case'a':return 0x55FF55;case'b':return 0x55FFFF;case'c':return 0xFF5555;case'd':return 0xFF55FF;case'e':return 0xFFFF55;case'f':return 0xFFFFFF;default:return-1;}}
    private static int healthColor(float ratio){return 0xFF000000|((int)(255*(1-ratio))<<16)|((int)(255*ratio)<<8);}
    private static String formatHealth(float health){return health==Math.round(health)?Integer.toString(Math.round(health)):String.format(Locale.ROOT,"%.1f",health);}
    private static double interpolate(double a,double b,float partial){return a+(b-a)*partial;}
}
