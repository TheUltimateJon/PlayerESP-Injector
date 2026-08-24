package playeresp.inject;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.lwjgl.opengl.GL11;

import java.util.List;

/** Renders only the selected player models. No world or block renderer state is reused. */
final class PlayerEspChamsRenderer {
    private static RenderManager owner;
    private static ChamsPlayerRenderer renderer;

    private PlayerEspChamsRenderer() { }

    static void render(RenderManager manager, List<PlayerEspProjectionRenderer.WorldEntry> entries,
                       float partialTicks, boolean drawChams, boolean drawOutline, float outlineWidth) {
        if (entries.isEmpty()) return;
        if (renderer == null || owner != manager) {
            owner = manager;
            renderer = new ChamsPlayerRenderer(manager);
        }

        boolean oldShadow = manager.isRenderShadow();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            manager.setRenderShadow(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_FOG);

            for (PlayerEspProjectionRenderer.WorldEntry entry : entries) {
                if (!(entry.player instanceof AbstractClientPlayer)) continue;
                if (drawChams) {
                    renderer.setChams(entry.color);
                    renderer.doRender((AbstractClientPlayer) entry.player,
                        entry.x - entry.originX, entry.y - entry.originY, entry.z - entry.originZ,
                        entry.yaw, partialTicks);
                }
                if (drawOutline) {
                    renderer.setOutline(entry.color, outlineWidth);
                    renderer.doRender((AbstractClientPlayer) entry.player,
                        entry.x - entry.originX, entry.y - entry.originY, entry.z - entry.originZ,
                        entry.yaw, partialTicks);
                }
            }
        } finally {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            manager.setRenderShadow(oldShadow);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    private static final class ChamsPlayerRenderer extends RenderPlayer {
        private float red = 1.0F;
        private float green = 1.0F;
        private float blue = 1.0F;
        private float lineWidth = 1.0F;
        private boolean outline;

        ChamsPlayerRenderer(RenderManager manager) {
            super(manager);
            shadowSize = 0.0F;
        }

        void setChams(int color) {
            red = (color >> 16 & 255) / 255.0F;
            green = (color >> 8 & 255) / 255.0F;
            blue = (color & 255) / 255.0F;
            outline = false;
        }

        void setOutline(int color, float width) {
            red = (color >> 16 & 255) / 255.0F;
            green = (color >> 8 & 255) / 255.0F;
            blue = (color & 255) / 255.0F;
            lineWidth = Math.max(0.5F, width);
            outline = true;
        }

        @Override
        protected void renderModel(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                   float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (outline) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
                GL11.glLineWidth(lineWidth);
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
            } else {
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            }
            GlStateManager.color(red, green, blue, 1.0F);
            super.renderModel(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scaleFactor);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glLineWidth(1.0F);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        protected void renderLayers(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                    float partialTicks, float ageInTicks, float netHeadYaw,
                                    float headPitch, float scaleIn) { }

        @Override
        protected boolean setDoRenderBrightness(AbstractClientPlayer player, float partialTicks) {
            return false;
        }

        @Override
        protected boolean canRenderName(AbstractClientPlayer player) {
            return false;
        }
    }
}
