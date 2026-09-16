package mchorse.bbs_mod.actions;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

public class SuperFakePlayerNetworkHandler extends ServerGamePacketListenerImpl
{
    private static final Connection FAKE_CONNECTION = new FakeClientConnection();

    public SuperFakePlayerNetworkHandler(ServerPlayer player)
    {
        super(player.level().getServer(), FAKE_CONNECTION, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener callbacks)
    {}

    private static final class FakeClientConnection extends Connection
    {
        private FakeClientConnection()
        {
            super(PacketFlow.CLIENTBOUND);
        }

        public void setPacketListener(PacketListener packetListener)
        {}
    }
}