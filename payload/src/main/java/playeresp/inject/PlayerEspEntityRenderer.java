package playeresp.inject;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

final class PlayerEspEntityRenderer extends Render<PlayerEspRenderEntity> {
    private final PlayerEspController controller;
    PlayerEspEntityRenderer(RenderManager manager,PlayerEspController controller){super(manager);this.controller=controller;}
    @Override public void doRender(PlayerEspRenderEntity entity,double x,double y,double z,float yaw,float partial){controller.onEntityRender(partial);}
    @Override public void doRenderShadowAndFire(Entity entity,double x,double y,double z,float yaw,float partial){ }
    @Override protected ResourceLocation getEntityTexture(PlayerEspRenderEntity entity){return null;}
}
