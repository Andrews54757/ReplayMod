package eu.crushedpixel.replaymod.events.handlers;

import eu.crushedpixel.replaymod.recording.ConnectionEventHandler;
import eu.crushedpixel.replaymod.utils.Objects;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.server.S14PacketEntity.S17PacketEntityLookMove;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.event.entity.minecart.MinecartInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerUseItemEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.ItemPickupEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;

public class RecordingHandler {

    private final Minecraft mc = Minecraft.getMinecraft();
    private Double lastX = null, lastY = null, lastZ = null;
    private ItemStack[] playerItems = new ItemStack[5];
    private int ticksSinceLastCorrection = 0;
    private boolean wasSleeping = false;
    private int lastRiding = -1;
    private Integer rotationYawHeadBefore = null;

    public void onPlayerJoin() {
        try {
            if(!ConnectionEventHandler.isRecording()) return;

            ConnectionEventHandler.insertPacket(spawnPlayer(mc.thePlayer));
        } catch(Exception e1) {
            e1.printStackTrace();
        }
    }

    public void onPlayerRespawn() {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            ConnectionEventHandler.insertPacket(spawnPlayer(mc.thePlayer));
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private S0CPacketSpawnPlayer spawnPlayer(EntityPlayer player) {
        try {
            S0CPacketSpawnPlayer packet = new S0CPacketSpawnPlayer();

            ByteBuf bb = Unpooled.buffer();
            PacketBuffer pb = new PacketBuffer(bb);

            pb.writeVarIntToBuffer(player.getEntityId());
            pb.writeUuid(EntityPlayer.getUUID(player.getGameProfile()));

            pb.writeInt(MathHelper.floor_double(player.posX * 32.0D));
            pb.writeInt(MathHelper.floor_double(player.posY * 32.0D));
            pb.writeInt(MathHelper.floor_double(player.posZ * 32.0D));
            pb.writeByte((byte) ((int) (player.rotationYaw * 256.0F / 360.0F)));
            pb.writeByte((byte) ((int) (player.rotationPitch * 256.0F / 360.0F)));

            ItemStack itemstack = player.inventory.getCurrentItem();
            pb.writeShort(itemstack == null ? 0 : Item.getIdFromItem(itemstack.getItem()));

            player.getDataWatcher().writeTo(pb);

            packet.readPacketData(pb);

            return packet;
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void resetVars() {
        lastX = lastY = lastZ = null;
        rotationYawHeadBefore = null;
        playerItems = new ItemStack[5];
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent e) {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            if(e.player != mc.thePlayer) return;
            if(!ConnectionEventHandler.isRecording()) return;

            boolean force = false;
            if(lastX == null || lastY == null || lastZ == null) {
                force = true;
                lastX = e.player.posX;
                lastY = e.player.posY;
                lastZ = e.player.posZ;
            }

            ticksSinceLastCorrection++;
            if(ticksSinceLastCorrection >= 100) {
                ticksSinceLastCorrection = 0;
                force = true;
            }

            double dx = e.player.posX - lastX;
            double dy = e.player.posY - lastY;
            double dz = e.player.posZ - lastZ;

            lastX = e.player.posX;
            lastY = e.player.posY;
            lastZ = e.player.posZ;

            Packet packet;
            if(force || Math.abs(dx) > 4.0 || Math.abs(dy) > 4.0 || Math.abs(dz) > 4.0) {
                int x = MathHelper.floor_double(e.player.posX * 32.0D);
                int y = MathHelper.floor_double(e.player.posY * 32.0D);
                int z = MathHelper.floor_double(e.player.posZ * 32.0D);
                byte yaw = (byte) ((int) (e.player.rotationYaw * 256.0F / 360.0F));
                byte pitch = (byte) ((int) (e.player.rotationPitch * 256.0F / 360.0F));
                packet = new S18PacketEntityTeleport(e.player.getEntityId(), x, y, z, yaw, pitch, e.player.onGround);
            } else {
                byte newYaw = (byte) ((int) (e.player.rotationYaw * 256.0F / 360.0F));
                byte newPitch = (byte) ((int) (e.player.rotationPitch * 256.0F / 360.0F));

                packet = new S17PacketEntityLookMove(e.player.getEntityId(),
                        (byte) Math.round(dx * 32), (byte) Math.round(dy * 32), (byte) Math.round(dz * 32),
                        newYaw, newPitch, e.player.onGround);
            }

            ConnectionEventHandler.insertPacket(packet);

            //HEAD POS
            int rotationYawHead = ((int)(e.player.rotationYawHead * 256.0F / 360.0F));

            if(!Objects.equals(rotationYawHead, rotationYawHeadBefore)) {
                S19PacketEntityHeadLook head = new S19PacketEntityHeadLook();
                ByteBuf bb1 = Unpooled.buffer();
                PacketBuffer pb1 = new PacketBuffer(bb1);

                pb1.writeVarIntToBuffer(e.player.getEntityId());
                pb1.writeByte(rotationYawHead);

                head.readPacketData(pb1);

                ConnectionEventHandler.insertPacket(head);

                rotationYawHeadBefore = rotationYawHead;
            }

            S12PacketEntityVelocity vel = new S12PacketEntityVelocity(e.player.getEntityId(), e.player.motionX, e.player.motionY, e.player.motionZ);
            ConnectionEventHandler.insertPacket(vel);

            //Animation Packets
            //Swing Animation
            if(e.player.swingProgressInt == 1) {
                S0BPacketAnimation pac = new S0BPacketAnimation();

                ByteBuf bb = Unpooled.buffer();
                PacketBuffer pb = new PacketBuffer(bb);

                pb.writeVarIntToBuffer(e.player.getEntityId());
                pb.writeByte(0);

                pac.readPacketData(pb);

                ConnectionEventHandler.insertPacket(pac);
            }

			/*
        //Potion Effect Handling
		List<Integer> found = new ArrayList<Integer>();
		for(PotionEffect pe : (Collection<PotionEffect>)e.player.getActivePotionEffects()) {
			found.add(pe.getPotionID());
			if(lastEffects.contains(found)) continue;
			S1DPacketEntityEffect pee = new S1DPacketEntityEffect(entityID, pe);
			ConnectionEventHandler.insertPacket(pee);
		}

		for(int id : lastEffects) {
			if(!found.contains(id)) {
				S1EPacketRemoveEntityEffect pre = new S1EPacketRemoveEntityEffect(entityID, new PotionEffect(id, 0));
				ConnectionEventHandler.insertPacket(pre);
			}
		}

		lastEffects = found;
			 */

            //Inventory Handling
            if(playerItems[0] != mc.thePlayer.getHeldItem()) {
                playerItems[0] = mc.thePlayer.getHeldItem();
                S04PacketEntityEquipment pee = new S04PacketEntityEquipment(e.player.getEntityId(), 0, playerItems[0]);
                ConnectionEventHandler.insertPacket(pee);
            }

            if(playerItems[1] != mc.thePlayer.inventory.armorInventory[0]) {
                playerItems[1] = mc.thePlayer.inventory.armorInventory[0];
                S04PacketEntityEquipment pee = new S04PacketEntityEquipment(e.player.getEntityId(), 1, playerItems[1]);
                ConnectionEventHandler.insertPacket(pee);
            }

            if(playerItems[2] != mc.thePlayer.inventory.armorInventory[1]) {
                playerItems[2] = mc.thePlayer.inventory.armorInventory[1];
                S04PacketEntityEquipment pee = new S04PacketEntityEquipment(e.player.getEntityId(), 2, playerItems[2]);
                ConnectionEventHandler.insertPacket(pee);
            }

            if(playerItems[3] != mc.thePlayer.inventory.armorInventory[2]) {
                playerItems[3] = mc.thePlayer.inventory.armorInventory[2];
                S04PacketEntityEquipment pee = new S04PacketEntityEquipment(e.player.getEntityId(), 3, playerItems[3]);
                ConnectionEventHandler.insertPacket(pee);
            }

            if(playerItems[4] != mc.thePlayer.inventory.armorInventory[3]) {
                playerItems[4] = mc.thePlayer.inventory.armorInventory[3];
                S04PacketEntityEquipment pee = new S04PacketEntityEquipment(e.player.getEntityId(), 4, playerItems[4]);
                ConnectionEventHandler.insertPacket(pee);
            }

            //Leaving Ride

            if((!mc.thePlayer.isRiding() && lastRiding != -1) ||
                    (mc.thePlayer.isRiding() && lastRiding != mc.thePlayer.ridingEntity.getEntityId())) {
                if(!mc.thePlayer.isRiding()) {
                    lastRiding = -1;
                } else {
                    lastRiding = mc.thePlayer.ridingEntity.getEntityId();
                }

                S1BPacketEntityAttach pea = new S1BPacketEntityAttach();

                ByteBuf buf = Unpooled.buffer();
                PacketBuffer pbuf = new PacketBuffer(buf);

                pbuf.writeInt(e.player.getEntityId());
                pbuf.writeInt(lastRiding);
                pbuf.writeBoolean(false);

                pea.readPacketData(pbuf);

                ConnectionEventHandler.insertPacket(pea);
            }

            //Sleeping
            if(!mc.thePlayer.isPlayerSleeping() && wasSleeping) {
                S0BPacketAnimation pac = new S0BPacketAnimation();

                ByteBuf bb = Unpooled.buffer();
                PacketBuffer pb = new PacketBuffer(bb);

                pb.writeVarIntToBuffer(e.player.getEntityId());
                pb.writeByte(2);

                pac.readPacketData(pb);

                ConnectionEventHandler.insertPacket(pac);

                wasSleeping = false;
            }

        } catch(Exception e1) {
            e1.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onPickupItem(ItemPickupEvent event) {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            ConnectionEventHandler.insertPacket(new S0DPacketCollectItem(event.pickedUp.getEntityId(), event.player.getEntityId()));
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onStartEating(PlayerUseItemEvent.Start event) {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            if(!event.entityPlayer.isEating()) return;
            S0BPacketAnimation packet = new S0BPacketAnimation();

            ByteBuf bb = Unpooled.buffer();
            PacketBuffer pb = new PacketBuffer(bb);

            pb.writeVarIntToBuffer(event.entityPlayer.getEntityId());
            pb.writeByte(3);

            packet.readPacketData(pb);

            ConnectionEventHandler.insertPacket(packet);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onSleep(PlayerSleepInBedEvent event) {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            if(event.entityPlayer != mc.thePlayer) {
                return;
            }

            System.out.println(event.getResult());
            S0APacketUseBed pub = new S0APacketUseBed();

            ByteBuf buf = Unpooled.buffer();
            PacketBuffer pbuf = new PacketBuffer(buf);

            pbuf.writeVarIntToBuffer(event.entityPlayer.getEntityId());
            pbuf.writeBlockPos(event.pos);

            pub.readPacketData(pbuf);

            ConnectionEventHandler.insertPacket(pub);

            wasSleeping = true;

        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void enterMinecart(MinecartInteractEvent event) {
        if(!ConnectionEventHandler.isRecording()) return;
        try {
            if(event.player != mc.thePlayer) {
                return;
            }

            S1BPacketEntityAttach pea = new S1BPacketEntityAttach();

            ByteBuf buf = Unpooled.buffer();
            PacketBuffer pbuf = new PacketBuffer(buf);

            pbuf.writeInt(event.player.getEntityId());
            pbuf.writeInt(event.minecart.getEntityId());
            pbuf.writeBoolean(false);

            pea.readPacketData(pbuf);

            ConnectionEventHandler.insertPacket(pea);

            lastRiding = event.minecart.getEntityId();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void checkForGamePaused(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!ConnectionEventHandler.isRecording()) return;
        if (mc.isIntegratedServerRunning()) {
            IntegratedServer server =  mc.getIntegratedServer();
            if (server != null && server.isGamePaused) {
                ConnectionEventHandler.notifyServerPaused();
            }
        }
    }
}
