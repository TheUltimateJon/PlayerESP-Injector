package playeresp.inject;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

final class PlayerEspRenderEntity extends Entity {
    PlayerEspRenderEntity(World world){super(world);ignoreFrustumCheck=true;noClip=true;setSize(0.01F,0.01F);setInvisible(true);}
    @Override protected void entityInit(){ }
    @Override protected void readEntityFromNBT(NBTTagCompound tag){ }
    @Override protected void writeEntityToNBT(NBTTagCompound tag){ }
    @Override public void onUpdate(){ }
    @Override public boolean canBeCollidedWith(){return false;}
    @Override public boolean canBePushed(){return false;}
    @Override public boolean isInRangeToRenderDist(double distance){return true;}
}
