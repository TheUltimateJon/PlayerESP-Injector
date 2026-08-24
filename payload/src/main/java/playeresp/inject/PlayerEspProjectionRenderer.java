package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Myau-style split renderer: world boxes in 3D, projected frames and labels in the HUD pass. */
final class PlayerEspProjectionRenderer {
    private static final Frustum FRUSTUM=new Frustum();
    private static final FloatBuffer MODELVIEW=BufferUtils.createFloatBuffer(16),PROJECTION=BufferUtils.createFloatBuffer(16),PROJECTED=BufferUtils.createFloatBuffer(3);
    private static final IntBuffer VIEWPORT=BufferUtils.createIntBuffer(16);
    private static volatile List<ScreenEntry> screenEntries=Collections.emptyList();
    private static boolean renderPositionProbed;
    private static Field renderPosXField,renderPosYField,renderPosZField;

    private PlayerEspProjectionRenderer(){}

    static void renderWorld(Minecraft mc,PlayerEspConfig config,List<EntityPlayer> players,float partial){
        RenderManager rm=mc.getRenderManager();Entity view=mc.getRenderViewEntity();if(view!=null)FRUSTUM.setPosition(view.posX,view.posY,view.posZ);
        double[] origin=renderOrigin(rm);boolean project=hasProjectedElements(config);if(project)readProjection();List<ScreenEntry> next=project?new ArrayList<ScreenEntry>():Collections.<ScreenEntry>emptyList();
        List<WorldEntry> worldEntries=new ArrayList<WorldEntry>();
        for(EntityPlayer player:players){
            AxisAlignedBB exact=interpolatedBox(player,partial);if(view!=null&&!player.ignoreFrustumCheck&&!FRUSTUM.isBoundingBoxInFrustum(exact.expand(0.1D,0.1D,0.1D)))continue;
            int color=PlayerEspRenderer.renderColor(player,config);
            if(project){ScreenEntry entry=projectPlayer(mc,player,exact.expand(0.1D,0.1D,0.1D),origin,color);if(entry!=null)next.add(entry);}
            double x=interpolate(player.lastTickPosX,player.posX,partial),y=interpolate(player.lastTickPosY,player.posY,partial),z=interpolate(player.lastTickPosZ,player.posZ,partial);
            float yaw=interpolateRotation(player.prevRotationYaw,player.rotationYaw,partial);
            worldEntries.add(new WorldEntry(player,exact.expand(0.12D,0.15D,0.12D).offset(-origin[0],-origin[1],-origin[2]),color,x,y,z,origin[0],origin[1],origin[2],yaw));
        }
        screenEntries=next;
        if(config.chams||config.boxMode==3)PlayerEspChamsRenderer.render(rm,worldEntries,partial,config.chams,config.boxMode==3,config.outlineThickness);
        if(config.boxMode==2)drawWorldOutlines(worldEntries,config.outlineThickness);
    }

    static void renderOverlay(Minecraft mc,PlayerEspConfig config){if(!hasProjectedElements(config)||screenEntries.isEmpty())return;int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();
        try{setupOverlayWithoutClearingDepth(mc);GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);GlStateManager.disableLighting();GlStateManager.disableFog();GlStateManager.disableDepth();GlStateManager.enableTexture2D();GlStateManager.color(1,1,1,1);drawEntries(mc,config);}
        finally{GlStateManager.enableDepth();GlStateManager.enableAlpha();GlStateManager.enableCull();GlStateManager.enableTexture2D();GlStateManager.disableBlend();GlStateManager.color(1,1,1,1);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(oldMode);}}

    static void setupOverlayWithoutClearingDepth(Minecraft mc){ScaledResolution resolution=new ScaledResolution(mc);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(0.0D,resolution.getScaledWidth_double(),resolution.getScaledHeight_double(),0.0D,1000.0D,3000.0D);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();GL11.glTranslatef(0.0F,0.0F,-2000.0F);}

    private static boolean hasProjectedElements(PlayerEspConfig config){return config.boxMode==1||config.healthBarPosition!=0||config.healthText||config.distance||config.nametag||config.armor||config.heldItem;}
    private static void readProjection(){MODELVIEW.clear();PROJECTION.clear();VIEWPORT.clear();GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,MODELVIEW);GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,PROJECTION);GL11.glGetInteger(GL11.GL_VIEWPORT,VIEWPORT);MODELVIEW.rewind();PROJECTION.rewind();VIEWPORT.rewind();}
    private static ScreenEntry projectPlayer(Minecraft mc,EntityPlayer player,AxisAlignedBB box,double[] origin,int color){float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;boolean found=false;double[] xs={box.minX,box.maxX},ys={box.minY,box.maxY},zs={box.minZ,box.maxZ};float scale=new ScaledResolution(mc).getScaleFactor();
        for(double x:xs)for(double y:ys)for(double z:zs){PROJECTED.clear();MODELVIEW.rewind();PROJECTION.rewind();VIEWPORT.rewind();if(!GLU.gluProject((float)(x-origin[0]),(float)(y-origin[1]),(float)(z-origin[2]),MODELVIEW,PROJECTION,VIEWPORT,PROJECTED))continue;float depth=PROJECTED.get(2);if(depth<0F||depth>=1F)continue;float sx=PROJECTED.get(0)/scale,sy=(mc.displayHeight-PROJECTED.get(1))/scale;minX=Math.min(minX,sx);minY=Math.min(minY,sy);maxX=Math.max(maxX,sx);maxY=Math.max(maxY,sy);found=true;}
        return found&&maxX>minX&&maxY>minY?new ScreenEntry(player,minX,minY,maxX,maxY,color):null;}

    private static double[] renderOrigin(RenderManager manager){probeRenderPosition(manager);try{return new double[]{renderPosXField.getDouble(manager),renderPosYField.getDouble(manager),renderPosZField.getDouble(manager)};}catch(Throwable ignored){return new double[]{manager.viewerPosX,manager.viewerPosY,manager.viewerPosZ};}}
    private static void probeRenderPosition(RenderManager manager){if(renderPositionProbed)return;renderPositionProbed=true;renderPosXField=findField(manager,"renderPosX","field_78725_b","o","field_4833");renderPosYField=findField(manager,"renderPosY","field_78726_c","p","field_4834");renderPosZField=findField(manager,"renderPosZ","field_78723_d","q","field_4835");}
    private static Field findField(Object owner,String...names){for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(String name:names)try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(Throwable ignored){}return null;}
    private static AxisAlignedBB interpolatedBox(EntityPlayer player,float partial){double x=interpolate(player.lastTickPosX,player.posX,partial),y=interpolate(player.lastTickPosY,player.posY,partial),z=interpolate(player.lastTickPosZ,player.posZ,partial);return player.getEntityBoundingBox().offset(x-player.posX,y-player.posY,z-player.posZ);}

    private static void drawWorldOutlines(List<WorldEntry> entries,float width){
        if(entries.isEmpty())return;GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try{GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glHint(GL11.GL_LINE_SMOOTH_HINT,GL11.GL_NICEST);GL11.glLineWidth(Math.max(0.5F,width));
            for(WorldEntry entry:entries)RenderGlobal.drawOutlinedBoundingBox(entry.box,entry.color>>16&255,entry.color>>8&255,entry.color&255,255);
        }finally{GL11.glPopAttrib();}}

    private static void drawEntries(Minecraft mc,PlayerEspConfig config){for(ScreenEntry entry:screenEntries){EntityPlayer player=entry.player;if(player==null||!player.isEntityAlive())continue;float x=entry.minX,y=entry.minY,z=entry.maxX,w=entry.maxY,width=Math.max(1F,config.outlineThickness);float health=Math.max(0,player.getHealth()+player.getAbsorptionAmount()),maximum=Math.max(1,player.getMaxHealth()),ratio=Math.max(0,Math.min(1,health/maximum));
            if(config.boxMode==1)drawScreenOutline(x,y,z,w,width,entry.color);
            if(config.healthBarPosition==1){float barY=y-4F;screenRect(x,barY,z,barY+2.5F,0xE0202020);screenRect(x+0.75F,barY+0.75F,x+0.75F+(z-x-1.5F)*ratio,barY+1.75F,healthColor(ratio));}
            else if(config.healthBarPosition==2){float barX=x-4F;screenRect(barX,y,barX+2.5F,w,0xE0202020);screenRect(barX+0.75F,w-0.75F-(w-y-1.5F)*ratio,barX+1.75F,w-0.75F,healthColor(ratio));}
            if(config.distance)mc.fontRendererObj.drawStringWithShadow(String.format(Locale.ROOT,"%.1fm",mc.thePlayer.getDistanceToEntity(player)),Math.round(z+4F),Math.round((y+w)/2F)-5,0xFFDDDDDD);
            drawTag(mc,entry,config,health,ratio);
        }}
    private static void drawScreenOutline(float x,float y,float z,float w,float width,int color){GlStateManager.disableTexture2D();GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glLineWidth(width);setColor(color);GL11.glBegin(GL11.GL_LINE_LOOP);GL11.glVertex2f(x,y);GL11.glVertex2f(z,y);GL11.glVertex2f(z,w);GL11.glVertex2f(x,w);GL11.glEnd();GL11.glLineWidth(1F);GL11.glDisable(GL11.GL_LINE_SMOOTH);GlStateManager.enableTexture2D();GlStateManager.color(1,1,1,1);}
    private static void drawTag(Minecraft mc,ScreenEntry entry,PlayerEspConfig config,float health,float healthRatio){EntityPlayer player=entry.player;float center=(entry.minX+entry.maxX)/2F,perspectiveScale=Math.max(0.45F,Math.min(3.0F,(entry.maxY-entry.minY)/68.0F));String visibleName=config.nametag?(config.colorMode==1?player.getName():player.getDisplayName().getFormattedText()):"",alignmentName=config.nametag?visibleName:player.getDisplayName().getFormattedText(),hp=config.healthText?formatHealth(health)+" HP":"";boolean hasTag=!visibleName.isEmpty()||!hp.isEmpty();float tagScale=perspectiveScale,tagY=entry.minY-2F-10F*tagScale;
        if(hasTag){GlStateManager.pushMatrix();GlStateManager.translate(center,tagY,0);GlStateManager.scale(tagScale,tagScale,1);int hw=mc.fontRendererObj.getStringWidth(hp),nw=mc.fontRendererObj.getStringWidth(alignmentName),gap=!hp.isEmpty()&&!alignmentName.isEmpty()?4:0;float nameX=-nw/2.0F,hpX=nameX-gap-hw;if(!hp.isEmpty()&&config.healthTextBackground)Gui.drawRect(Math.round(hpX)-2,-2,Math.round(hpX)+hw+2,10,0xEE030303);if(!visibleName.isEmpty()&&config.nametagBackground)Gui.drawRect(Math.round(nameX)-2,-2,Math.round(nameX)+nw+2,10,0xEE030303);if(!hp.isEmpty())mc.fontRendererObj.drawStringWithShadow(hp,hpX,0,healthColor(healthRatio));if(!visibleName.isEmpty())mc.fontRendererObj.drawStringWithShadow(visibleName,nameX,0,config.colorMode==1?entry.color:0xFFFFFFFF);GlStateManager.popMatrix();}
        if(config.armor||config.heldItem){float equipmentBottom=hasTag?tagY-3F:entry.minY-3F;drawEquipment(mc,player,config,center,equipmentBottom-16F*perspectiveScale,perspectiveScale);}}
    private static void drawEquipment(Minecraft mc,EntityPlayer player,PlayerEspConfig config,float center,float y,float scale){List<ItemStack> stacks=new ArrayList<ItemStack>();if(config.armor)for(int slot=3;slot>=0;slot--){ItemStack stack=player.getCurrentArmor(slot);if(stack!=null)stacks.add(stack);}if(config.heldItem&&player.getHeldItem()!=null)stacks.add(player.getHeldItem());if(stacks.isEmpty())return;RenderItem renderer=mc.getRenderItem();float totalWidth=stacks.size()*18F*scale;GlStateManager.pushMatrix();try{GlStateManager.translate(center-totalWidth/2F,y,0);GlStateManager.scale(scale,scale,1);GlStateManager.enableTexture2D();GlStateManager.enableDepth();RenderHelper.enableGUIStandardItemLighting();int x=0;for(ItemStack stack:stacks){renderer.renderItemAndEffectIntoGUI(stack,x,0);renderer.renderItemOverlays(mc.fontRendererObj,stack,x,0);x+=18;}}finally{RenderHelper.disableStandardItemLighting();GlStateManager.disableDepth();GlStateManager.enableBlend();GlStateManager.color(1,1,1,1);GlStateManager.popMatrix();}}
    private static void screenRect(float x1,float y1,float x2,float y2,int color){Gui.drawRect(Math.round(x1),Math.round(y1),Math.round(x2),Math.round(y2),color);}
    private static void setColor(int color){GlStateManager.color((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,(color>>>24&255)/255F);}
    private static int healthColor(float ratio){return 0xFF000000|((int)(255*(1-ratio))<<16)|((int)(255*ratio)<<8);}
    private static String formatHealth(float health){return health==Math.round(health)?Integer.toString(Math.round(health)):String.format(Locale.ROOT,"%.1f",health);}
    private static double interpolate(double a,double b,float partial){return a+(b-a)*partial;}
    private static float interpolateRotation(float previous,float current,float partial){float delta=current-previous;while(delta<-180F)delta+=360F;while(delta>=180F)delta-=360F;return previous+partial*delta;}
    static final class WorldEntry {final EntityPlayer player;final AxisAlignedBB box;final int color;final double x,y,z,originX,originY,originZ;final float yaw;WorldEntry(EntityPlayer player,AxisAlignedBB box,int color,double x,double y,double z,double originX,double originY,double originZ,float yaw){this.player=player;this.box=box;this.color=color;this.x=x;this.y=y;this.z=z;this.originX=originX;this.originY=originY;this.originZ=originZ;this.yaw=yaw;}}
    private static final class ScreenEntry {final EntityPlayer player;final float minX,minY,maxX,maxY;final int color;ScreenEntry(EntityPlayer player,float minX,float minY,float maxX,float maxY,int color){this.player=player;this.minX=minX;this.minY=minY;this.maxX=maxX;this.maxY=maxY;this.color=color;}}
}
