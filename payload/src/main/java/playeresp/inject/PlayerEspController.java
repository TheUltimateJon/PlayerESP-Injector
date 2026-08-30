package playeresp.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class PlayerEspController {
    private static final int WORLD_GL_STATE_MASK=GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT
        |GL11.GL_LINE_BIT|GL11.GL_POLYGON_BIT|GL11.GL_TEXTURE_BIT|GL11.GL_CURRENT_BIT
        |GL11.GL_LIGHTING_BIT|GL11.GL_FOG_BIT|GL11.GL_TRANSFORM_BIT;
    private static final int FALLBACK_ENTITY_ID=Integer.MIN_VALUE+190;
    private static final String ACTIVE_PROPERTY="toolbox.playeresp.active";
    private final Minecraft mc=Minecraft.getMinecraft();
    private final PlayerEspConfig config=PlayerEspConfig.load();
    private final String instanceToken=Long.toHexString(System.nanoTime())+'-'+Integer.toHexString(System.identityHashCode(this));
    private boolean menuWasDown,toggleWasDown,fallbackInstalled,renderEnvironmentProbed;
    private World trackedWorld;
    private WorldClient fallbackWorld;
    private PlayerEspRenderEntity fallbackEntity;
    private Field shaderShadowPassField;
    private Field timerField,renderPartialTicksField;
    private Method forgeRenderPassMethod;
    private List<EntityPlayer> cachedPlayers=Collections.emptyList();
    private long lastWorldRenderNanos,lastOverlayRenderNanos,lastOverlayCallbackNanos;
    private float latestPartialTicks=1.0F;
    private final ScheduledExecutorService saveExecutor=Executors.newSingleThreadScheduledExecutor(new ThreadFactory(){@Override public Thread newThread(Runnable task){Thread thread=new Thread(task,"PlayerESP Config Save");thread.setDaemon(true);return thread;}});
    private ScheduledFuture<?> pendingSave;
    private final Map<String,HiddenNameState> hiddenNames=new HashMap<String,HiddenNameState>();
    private Scoreboard hiddenScoreboard;
    private int hiddenTeamSequence;

    public PlayerEspController(){System.setProperty(ACTIVE_PROPERTY,instanceToken);}

    public void onTick(){if(!isActiveInstance())return;updateKeys();if(mc.theWorld!=trackedWorld){restoreVanillaNametags();trackedWorld=mc.theWorld;fallbackEntity=null;cachedPlayers=Collections.emptyList();PlayerEspProjectionRenderer.clear();}if(config.enabled&&mc.theWorld!=null&&mc.thePlayer!=null)cachedPlayers=PlayerEspRenderer.collectPlayers(mc,config);else{cachedPlayers=Collections.emptyList();PlayerEspProjectionRenderer.clear();}updateVanillaNametags();ensureFallbackEntity();}
    private void updateKeys(){boolean menu=config.menuKey!=Keyboard.KEY_NONE&&Keyboard.isKeyDown(config.menuKey);boolean toggle=config.toggleKey!=Keyboard.KEY_NONE&&Keyboard.isKeyDown(config.toggleKey);
        if(mc.currentScreen==null&&menu&&!menuWasDown)mc.displayGuiScreen(new PlayerEspScreen(this,config));else if(mc.currentScreen==null&&toggle&&!toggleWasDown){config.enabled=!config.enabled;save();}menuWasDown=menu;toggleWasDown=toggle;}
    void onEntityRender(final float partial){
        if(!isActiveInstance()||!config.enabled||mc.thePlayer==null||mc.theWorld==null||isSecondaryPass())return;
        latestPartialTicks=partial;
        long now=System.nanoTime();
        if(now-lastOverlayCallbackNanos>250000000L)renderWorld(now,partial);
    }
    public void onOverlayRender(int pass){
        if(!isActiveInstance()||mc.thePlayer==null||mc.theWorld==null||pass!=0||isSecondaryPass())return;
        long now=System.nanoTime();lastOverlayCallbackNanos=now;float partial=currentPartialTicks();latestPartialTicks=partial;renderWorld(now,partial);
    }
    void onHudRender(float partial){if(!isActiveInstance()||!config.enabled||mc.thePlayer==null||mc.theWorld==null)return;latestPartialTicks=partial;renderOverlay(System.nanoTime());}
    private void renderWorld(long now,final float partial){
        if(!config.enabled||now-lastWorldRenderNanos<5000000L)return;lastWorldRenderNanos=now;
        RenderAction action=new RenderAction(){@Override public void run(){PlayerEspRenderer.renderWorld(mc,config,cachedPlayers,partial);}};
        if(PlayerEspRenderer.needsWorldStateGuard(config))renderSafely(action);else action.run();
    }
    private void renderOverlay(long now){
        if(!config.enabled||!PlayerEspRenderer.needsOverlay(config)||now-lastOverlayRenderNanos<5000000L)return;lastOverlayRenderNanos=now;
        PlayerEspRenderer.renderOverlay(mc,config);
    }
    private void renderSafely(RenderAction action){
        int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE),oldActiveTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),blendSrcRgb=GL11.glGetInteger(0x80C9),blendDstRgb=GL11.glGetInteger(0x80C8),blendSrcAlpha=GL11.glGetInteger(0x80CB),blendDstAlpha=GL11.glGetInteger(0x80CA);
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);int defaultTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);boolean defaultTextureEnabled=GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);int lightmapTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);boolean lightmapTextureEnabled=GL11.glIsEnabled(GL11.GL_TEXTURE_2D);GL13.glActiveTexture(oldActiveTexture);
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),lighting=GL11.glIsEnabled(GL11.GL_LIGHTING),fog=GL11.glIsEnabled(GL11.GL_FOG),alpha=GL11.glIsEnabled(GL11.GL_ALPHA_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glPushAttrib(WORLD_GL_STATE_MASK);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();
        try{action.run();}finally{GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(oldMode);GL11.glPopAttrib();syncRenderState(depth,blend,defaultTextureEnabled,lightmapTextureEnabled,lighting,fog,alpha,cull,depthMask,oldActiveTexture,defaultTexture,lightmapTexture,blendSrcRgb,blendDstRgb,blendSrcAlpha,blendDstAlpha);}
    }
    private interface RenderAction{void run();}
    void onFallbackTick(){onTick();}
    void enableFallbackRendering(){if(fallbackInstalled)return;RenderManager manager=mc.getRenderManager();Map map=findRendererMap(manager);if(map==null)throw new IllegalStateException("Minecraft entity renderer map was not found.");map.put(PlayerEspRenderEntity.class,new PlayerEspEntityRenderer(manager,this));fallbackInstalled=true;}
    void enableFallbackHud(){if(mc.ingameGUI==null)return;if(mc.ingameGUI instanceof PlayerEspHudBridge){((PlayerEspHudBridge)mc.ingameGUI).setController(this);return;}mc.ingameGUI=new PlayerEspHudBridge(mc,mc.ingameGUI,this);}
    private void ensureFallbackEntity(){if(!fallbackInstalled||mc.theWorld==null||mc.thePlayer==null)return;if(fallbackWorld!=mc.theWorld){if(fallbackWorld!=null&&fallbackEntity!=null)fallbackWorld.removeEntityFromWorld(FALLBACK_ENTITY_ID);fallbackWorld=mc.theWorld;fallbackEntity=null;}
        if(fallbackEntity==null||fallbackWorld.getEntityByID(FALLBACK_ENTITY_ID)!=fallbackEntity){fallbackEntity=new PlayerEspRenderEntity(fallbackWorld);fallbackWorld.addEntityToWorld(FALLBACK_ENTITY_ID,fallbackEntity);}fallbackEntity.setPosition(mc.thePlayer.posX,mc.thePlayer.posY,mc.thePlayer.posZ);fallbackEntity.lastTickPosX=fallbackEntity.prevPosX=fallbackEntity.posX;fallbackEntity.lastTickPosY=fallbackEntity.prevPosY=fallbackEntity.posY;fallbackEntity.lastTickPosZ=fallbackEntity.prevPosZ=fallbackEntity.posZ;}
    private Map findRendererMap(RenderManager manager){for(Class<?> type=manager.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields()){if(!Map.class.isAssignableFrom(field.getType()))continue;try{field.setAccessible(true);Object value=field.get(manager);if(!(value instanceof Map))continue;Map map=(Map)value;for(Object object:map.entrySet()){Map.Entry entry=(Map.Entry)object;if(entry.getKey() instanceof Class&&Entity.class.isAssignableFrom((Class<?>)entry.getKey())&&entry.getValue() instanceof Render)return map;}}catch(Throwable ignored){}}return null;}
    private boolean isSecondaryPass(){probeRenderEnvironment();try{if(shaderShadowPassField!=null&&shaderShadowPassField.getBoolean(null))return true;}catch(Throwable ignored){}try{if(forgeRenderPassMethod!=null){Object v=forgeRenderPassMethod.invoke(null);if(v instanceof Number&&((Number)v).intValue()>0)return true;}}catch(Throwable ignored){}return false;}
    private float currentPartialTicks(){try{if(timerField==null){timerField=findNamedField(mc,"timer","field_71428_T");if(timerField==null)return latestPartialTicks;Object timer=timerField.get(mc);if(timer!=null)renderPartialTicksField=findNamedField(timer,"renderPartialTicks","field_74281_c","c");}Object timer=timerField.get(mc);return timer!=null&&renderPartialTicksField!=null?renderPartialTicksField.getFloat(timer):latestPartialTicks;}catch(Throwable ignored){return latestPartialTicks;}}
    private static Field findNamedField(Object owner,String...names){for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(String name:names)try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(Throwable ignored){}return null;}
    private void probeRenderEnvironment(){if(renderEnvironmentProbed)return;renderEnvironmentProbed=true;for(String name:new String[]{"net.optifine.shaders.Shaders","shadersmod.client.Shaders"})try{Class<?> type=Class.forName(name,false,mc.getClass().getClassLoader());shaderShadowPassField=type.getDeclaredField("isShadowPass");shaderShadowPassField.setAccessible(true);break;}catch(Throwable ignored){}
        try{Class<?> type=Class.forName("net.minecraftforge.client.MinecraftForgeClient",false,mc.getClass().getClassLoader());forgeRenderPassMethod=type.getMethod("getRenderPass");}catch(Throwable ignored){}}
    public synchronized void save(){if(pendingSave!=null)pendingSave.cancel(false);pendingSave=saveExecutor.schedule(new Runnable(){@Override public void run(){config.save();}},200L,TimeUnit.MILLISECONDS);}
    void suppressMenuUntilRelease(){menuWasDown=true;}
    private boolean isActiveInstance(){return instanceToken.equals(System.getProperty(ACTIVE_PROPERTY));}
    private static void syncRenderState(boolean depth,boolean blend,boolean defaultTextureEnabled,boolean lightmapTextureEnabled,boolean lighting,boolean fog,boolean alpha,boolean cull,boolean depthMask,int activeTexture,int defaultTexture,int lightmapTexture,int srcRgb,int dstRgb,int srcAlpha,int dstAlpha){
        setRawCapability(GL11.GL_DEPTH_TEST,depth);setRawCapability(GL11.GL_BLEND,blend);setRawCapability(GL11.GL_LIGHTING,lighting);setRawCapability(GL11.GL_FOG,fog);setRawCapability(GL11.GL_ALPHA_TEST,alpha);setRawCapability(GL11.GL_CULL_FACE,cull);GL11.glDepthMask(depthMask);org.lwjgl.opengl.GL14.glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);setRawCapability(GL11.GL_TEXTURE_2D,defaultTextureEnabled);GL11.glBindTexture(GL11.GL_TEXTURE_2D,defaultTexture);
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);setRawCapability(GL11.GL_TEXTURE_2D,lightmapTextureEnabled);GL11.glBindTexture(GL11.GL_TEXTURE_2D,lightmapTexture);GL13.glActiveTexture(activeTexture);GL11.glColor4f(1,1,1,1);
        if(depth)GlStateManager.enableDepth();else GlStateManager.disableDepth();if(blend)GlStateManager.enableBlend();else GlStateManager.disableBlend();if(lighting)GlStateManager.enableLighting();else GlStateManager.disableLighting();if(fog)GlStateManager.enableFog();else GlStateManager.disableFog();if(alpha)GlStateManager.enableAlpha();else GlStateManager.disableAlpha();if(cull)GlStateManager.enableCull();else GlStateManager.disableCull();GlStateManager.depthMask(depthMask);GlStateManager.tryBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);if(defaultTextureEnabled)GlStateManager.enableTexture2D();else GlStateManager.disableTexture2D();GlStateManager.bindTexture(defaultTexture);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);if(lightmapTextureEnabled)GlStateManager.enableTexture2D();else GlStateManager.disableTexture2D();GlStateManager.bindTexture(lightmapTexture);GlStateManager.setActiveTexture(activeTexture);GlStateManager.color(1,1,1,1);
    }
    private static void setRawCapability(int capability,boolean enabled){if(enabled)GL11.glEnable(capability);else GL11.glDisable(capability);}
    private void updateVanillaNametags(){
        if(!config.enabled||!config.nametag||mc.theWorld==null){restoreVanillaNametags();return;}
        Scoreboard scoreboard=mc.theWorld.getScoreboard();if(hiddenScoreboard!=scoreboard){restoreVanillaNametags();hiddenScoreboard=scoreboard;}
        Map<String,EntityPlayer> desired=new HashMap<String,EntityPlayer>();for(EntityPlayer player:cachedPlayers)desired.put(player.getName(),player);
        Iterator<Map.Entry<String,HiddenNameState>> iterator=hiddenNames.entrySet().iterator();while(iterator.hasNext()){Map.Entry<String,HiddenNameState> entry=iterator.next();if(!desired.containsKey(entry.getKey())){restoreHiddenName(entry.getKey(),entry.getValue(),true);iterator.remove();}}
        for(String name:desired.keySet()){HiddenNameState state=hiddenNames.get(name);ScorePlayerTeam current=scoreboard.getPlayersTeam(name);if(state!=null&&current==state.hiddenTeam){copyTeamProperties(state.originalTeam,state.hiddenTeam);continue;}if(state!=null){restoreHiddenName(name,state,false);hiddenNames.remove(name);}ScorePlayerTeam hidden=createHiddenTeam(scoreboard,current);if(scoreboard.addPlayerToTeam(name,hidden.getRegisteredName()))hiddenNames.put(name,new HiddenNameState(current,hidden));else scoreboard.removeTeam(hidden);}
    }
    private ScorePlayerTeam createHiddenTeam(Scoreboard scoreboard,ScorePlayerTeam original){String name;do{name="peh"+Integer.toHexString(hiddenTeamSequence++);}while(scoreboard.getTeam(name)!=null);ScorePlayerTeam hidden=scoreboard.createTeam(name);copyTeamProperties(original,hidden);return hidden;}
    private static void copyTeamProperties(ScorePlayerTeam source,ScorePlayerTeam target){String prefix=source==null?"":source.getColorPrefix(),suffix=source==null?"":source.getColorSuffix();boolean friendlyFire=source==null||source.getAllowFriendlyFire(),friendlyInvisible=source!=null&&source.getSeeFriendlyInvisiblesEnabled();Team.EnumVisible death=source==null?Team.EnumVisible.ALWAYS:source.getDeathMessageVisibility();if(!prefix.equals(target.getColorPrefix()))target.setNamePrefix(prefix);if(!suffix.equals(target.getColorSuffix()))target.setNameSuffix(suffix);if(friendlyFire!=target.getAllowFriendlyFire())target.setAllowFriendlyFire(friendlyFire);if(friendlyInvisible!=target.getSeeFriendlyInvisiblesEnabled())target.setSeeFriendlyInvisiblesEnabled(friendlyInvisible);if(death!=target.getDeathMessageVisibility())target.setDeathMessageVisibility(death);if(source!=null&&source.getChatFormat()!=target.getChatFormat())target.setChatFormat(source.getChatFormat());if(target.getNameTagVisibility()!=Team.EnumVisible.NEVER)target.setNameTagVisibility(Team.EnumVisible.NEVER);}
    private void restoreHiddenName(String name,HiddenNameState state,boolean restoreMembership){if(hiddenScoreboard==null)return;try{if(restoreMembership&&hiddenScoreboard.getPlayersTeam(name)==state.hiddenTeam){if(state.originalTeam!=null&&hiddenScoreboard.getTeam(state.originalTeam.getRegisteredName())==state.originalTeam)hiddenScoreboard.addPlayerToTeam(name,state.originalTeam.getRegisteredName());else hiddenScoreboard.removePlayerFromTeams(name);}hiddenScoreboard.removeTeam(state.hiddenTeam);}catch(Throwable ignored){}}
    private void restoreVanillaNametags(){if(hiddenScoreboard!=null)for(Map.Entry<String,HiddenNameState> entry:hiddenNames.entrySet())restoreHiddenName(entry.getKey(),entry.getValue(),true);hiddenNames.clear();hiddenScoreboard=null;}
    private static final class HiddenNameState{final ScorePlayerTeam originalTeam,hiddenTeam;HiddenNameState(ScorePlayerTeam originalTeam,ScorePlayerTeam hiddenTeam){this.originalTeam=originalTeam;this.hiddenTeam=hiddenTeam;}}
}
