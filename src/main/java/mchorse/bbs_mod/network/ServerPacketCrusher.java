package mchorse.bbs_mod.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;

public class ServerPacketCrusher extends PacketCrusher
{
    @Override
    protected void sendBuffer(Player entity, Identifier identifier, FriendlyByteBuf buf)
    {
        ServerPlayNetworking.send((ServerPlayer) entity, ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(identifier)));
    }
}