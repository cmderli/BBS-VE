package mchorse.bbs_mod.network;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.structure.StructureCut;
import mchorse.bbs_mod.forms.structure.StructureWand;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.entity.IEntityFormProvider;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ClientNetwork
{
    private static int ids = 0;
    private static Map<Integer, Consumer<BaseType>> callbacks = new HashMap<>();
    private static ClientPacketCrusher crusher = new ClientPacketCrusher();

    private static boolean isBBSModOnServer;

    public static void resetHandshake()
    {
        isBBSModOnServer = false;
        crusher.reset();
    }

    public static boolean isIsBBSModOnServer()
    {
        return isBBSModOnServer;
    }

    /* Network */

    public static void setup()
    {
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_CLICKED_MODEL_BLOCK_PACKET), (payload, context) -> handleClientModelBlockPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_PLAYER_FORM_PACKET), (payload, context) -> handlePlayerFormPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_PLAY_FILM_PACKET), (payload, context) -> handlePlayFilmPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_MANAGER_DATA_PACKET), (payload, context) -> handleManagerDataPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_STOP_FILM_PACKET), (payload, context) -> handleStopFilmPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_HANDSHAKE), (payload, context) -> handleHandshakePacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_RECORDED_ACTIONS), (payload, context) -> handleRecordedActionsPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_ANIMATION_STATE_TRIGGER), (payload, context) -> handleFormTriggerPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_CHEATS_PERMISSION), (payload, context) -> handleCheatsPermissionPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_SHARED_FORM), (payload, context) -> handleShareFormPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_ENTITY_FORM), (payload, context) -> handleEntityFormPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_ACTORS), (payload, context) -> handleActorsPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_GUN_PROPERTIES), (payload, context) -> handleGunPropertiesPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_PAUSE_FILM), (payload, context) -> handlePauseFilmPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_SELECTED_SLOT), (payload, context) -> handleSelectedSlotPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER), (payload, context) -> handleAnimationStateModelBlockPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_REFRESH_MODEL_BLOCKS), (payload, context) -> handleRefreshModelBlocksPacket(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_REQUEST_FILM_RESYNC), (payload, context) -> handleRequestFilmResync(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_STRUCTURE_SAVED), (payload, context) -> handleStructureSaved(context.client(), payload.asPacketByteBuf()));
        ClientPlayNetworking.registerGlobalReceiver(ServerNetwork.idFor(ServerNetwork.CLIENT_STRUCTURE_CUT), (payload, context) -> handleStructureCut(context.client(), payload.asPacketByteBuf()));
    }

    /* Handlers */

    /** The server's answer to a film cut: whether the file got written and the region emptied. */
    private static void handleStructureCut(Minecraft client, FriendlyByteBuf buf)
    {
        boolean ok = buf.readBoolean();
        String name = buf.readUtf();

        client.execute(() -> StructureCut.onCut(ok, name));
    }

    /** The server's answer to the wand: whether the file got written, and under which id. */
    private static void handleStructureSaved(Minecraft client, FriendlyByteBuf buf)
    {
        boolean saved = buf.readBoolean();
        String name = buf.readUtf();

        client.execute(() -> StructureWand.onSaved(saved, name));
    }

    private static void handleClientModelBlockPacket(Minecraft client, FriendlyByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();

        client.execute(() ->
        {
            BlockEntity entity = client.level.getBlockEntity(pos);

            if (!(entity instanceof ModelBlockEntity))
            {
                return;
            }

            UIBaseMenu menu = UIScreen.getCurrentMenu();
            UIDashboard dashboard = BBSModClient.getDashboard();

            if (menu != dashboard)
            {
                UIScreen.open(dashboard);
            }

            UIModelBlockPanel panel = dashboard.getPanels().getPanel(UIModelBlockPanel.class);

            dashboard.setPanel(panel);
            panel.fill((ModelBlockEntity) entity, true);
        });
    }

    private static void handlePlayerFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            int id = packetByteBuf.readInt();
            Form form = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            final Form finalForm = form;

            client.execute(() ->
            {
                Entity entity = client.level.getEntityById(id);
                Morph morph = Morph.getMorph(entity);

                if (morph != null)
                {
                    morph.setForm(finalForm);
                }
            });
        });
    }

    private static void handlePlayFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readUtf();
            boolean withCamera = packetByteBuf.readBoolean();
            Film film = new Film();

            film.setId(filmId);
            film.fromData(DataStorageUtils.readFromBytes(bytes));

            client.execute(() -> Films.playFilm(film, withCamera));
        });
    }

    private static void handleManagerDataPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            int callbackId = packetByteBuf.readInt();
            RepositoryOperation op = RepositoryOperation.values()[packetByteBuf.readInt()];
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            client.execute(() ->
            {
                Consumer<BaseType> callback = callbacks.remove(callbackId);

                if (callback != null)
                {
                    callback.accept(data);
                }
            });
        });
    }

    private static void handleStopFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() -> Films.stopFilm(filmId));
    }

    private static void handleRequestFilmResync(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() ->
        {
            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);
            Film film = panel == null ? null : panel.getData();

            /* Server lost track of a path we edited — re-send the whole film
             * (root path) so it can rebuild its copy via film.fromData(...). */
            if (film != null && film.getId().equals(filmId))
            {
                sendSyncData(filmId, film);
            }
        });
    }

    private static void handleHandshakePacket(Minecraft client, FriendlyByteBuf buf)
    {
        isBBSModOnServer = true;
    }

    private static void handleRecordedActionsPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readUtf();
            int replayId = packetByteBuf.readInt();
            int tick = packetByteBuf.readInt();
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            client.execute(() ->
            {
                BBSModClient.getDashboard().getPanels().getPanel(UIFilmPanel.class).receiveActions(filmId, replayId, tick, data);
            });
        });
    }

    private static void handleFormTriggerPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int id = buf.readInt();
        String triggerId = buf.readUtf();
        int type = buf.readInt();

        client.execute(() ->
        {
            Entity entity = client.level.getEntityById(id);
            Morph morph = Morph.getMorph(entity);

            if (morph != null && morph.getForm() != null)
            {
                morph.getForm().playState(triggerId);
            }

            if (entity instanceof LivingEntity livingEntity && type > 0)
            {
                ItemStack stackInHand = livingEntity.getItemInHand(type == 1 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                ModelProperties properties = BBSModClient.getItemStackProperties(stackInHand);

                if (properties != null && properties.getForm() != null)
                {
                    properties.getForm().playState(triggerId);
                }
            }
        });
    }

    private static void handleCheatsPermissionPacket(Minecraft client, FriendlyByteBuf buf)
    {
        boolean cheats = buf.readBoolean();

        client.execute(() ->
        {
            client.player.setPermissions(cheats ? PermissionSet.ALL_PERMISSIONS : PermissionSet.NO_PERMISSIONS);
        });
    }

    private static void handleShareFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            final Form finalForm = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            if (finalForm == null)
            {
                return;
            }

            client.execute(() ->
            {
                UIBaseMenu menu = UIScreen.getCurrentMenu();
                UIDashboard dashboard = BBSModClient.getDashboard();

                if (menu == null)
                {
                    UIScreen.open(dashboard);
                }

                dashboard.setPanel(dashboard.getPanel(UIMorphingPanel.class));
                BBSModClient.getFormCategories().getRecentForms().getCategories().get(0).addForm(finalForm);
                dashboard.context.notifyInfo(UIKeys.FORMS_SHARED_NOTIFICATION.format(finalForm.getDisplayName()));
            });
        });
    }

    private static void handleEntityFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            final Form finalForm = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            if (finalForm == null)
            {
                return;
            }

            int entityId = buf.readInt();

            client.execute(() ->
            {
                Entity entity = client.level.getEntityById(entityId);

                if (entity instanceof IEntityFormProvider provider)
                {
                    provider.setForm(finalForm);
                }
            });
        });
    }

    private static void handleActorsPacket(Minecraft client, FriendlyByteBuf buf)
    {
        Map<String, Integer> actors = new HashMap<>();
        String filmId = buf.readUtf();
        boolean merge = buf.readBoolean();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            String key = buf.readUtf();
            int entityId = buf.readInt();

            actors.put(key, entityId);
        }

        client.execute(() ->
        {
            BBSModClient.getFilms().updateActors(filmId, actors, merge);

            /* Only if the dashboard is already there: building it costs the whole UI, and this
             * packet reaches every player in the world - including ones who never opened BBS. */
            UIDashboard dashboard = BBSModClient.getDashboardIfCreated();
            UIFilmPanel panel = dashboard == null ? null : dashboard.getPanel(UIFilmPanel.class);

            if (panel != null)
            {
                panel.updateActors(filmId, BBSModClient.getFilms().getActors(filmId));
            }
        });
    }

    private static void handleGunPropertiesPacket(Minecraft client, FriendlyByteBuf buf)
    {
        GunProperties properties = new GunProperties();
        int entityId = buf.readInt();

        properties.fromNetwork(buf);

        client.execute(() ->
        {
            Entity entity = client.level.getEntityById(entityId);

            if (entity instanceof GunProjectileEntity projectile)
            {
                projectile.setProperties(properties);
                projectile.refreshDimensions();
            }
        });
    }

    private static void handlePauseFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() ->
        {
            Films.togglePauseFilm(filmId);
        });
    }

    private static void handleSelectedSlotPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int slot = buf.readInt();

        client.execute(() ->
        {
            client.player.getInventory().setSelectedSlot(slot);
        });
    }

    private static void handleAnimationStateModelBlockPacket(Minecraft client, FriendlyByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();
        String state = buf.readUtf();

        client.execute(() ->
        {
            BlockEntity blockEntity = client.level.getBlockEntity(pos);

            if (blockEntity instanceof ModelBlockEntity block)
            {
                if (block.getProperties().getForm() != null)
                {
                    block.getProperties().getForm().playState(state);
                }
            }
        });
    }

    private static void handleRefreshModelBlocksPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int range = buf.readInt();

        client.execute(() ->
        {
            for (ModelBlockEntity mb : BBSRendering.capturedModelBlocks)
            {
                ModelProperties properties = mb.getProperties();
                int random = (int) (Math.random() * range);

                properties.setForm(FormUtils.copy(properties.getForm()));

                while (random > 0)
                {
                    properties.update(mb.getEntity());

                    random -= 1;
                }
            }
        });
    }

    /* API */
    
    public static void sendModelBlockForm(BlockPos pos, ModelBlockEntity modelBlock)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_FORM_PACKET, modelBlock.getProperties().toData(), (packetByteBuf) ->
        {
            packetByteBuf.writeBlockPos(pos);
        });
    }

    public static void sendPlayerForm(Form form)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_PLAYER_FORM_PACKET, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {});
    }

    public static void sendModelBlockTransforms(MapType data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, data, (packetByteBuf) ->
        {});
    }

    public static void sendManagerDataLoad(String id, Consumer<BaseType> consumer)
    {
        MapType mapType = new MapType();

        mapType.putString("id", id);
        ClientNetwork.sendManagerData(RepositoryOperation.LOAD, mapType, consumer);
    }

    public static void sendManagerData(RepositoryOperation op, BaseType data, Consumer<BaseType> consumer)
    {
        int id = ids;

        callbacks.put(id, consumer);
        sendManagerData(id, op, data);

        ids += 1;
    }

    public static void sendManagerData(int callbackId, RepositoryOperation op, BaseType data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MANAGER_DATA_PACKET, data, (packetByteBuf) ->
        {
            packetByteBuf.writeInt(callbackId);
            packetByteBuf.writeInt(op.ordinal());
        });
    }

    public static void sendActionRecording(String filmId, int replayId, int tick, int countdown, boolean state)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(filmId);
        buf.writeInt(replayId);
        buf.writeInt(tick);
        buf.writeInt(countdown);
        buf.writeBoolean(state);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_ACTION_RECORDING)));
    }

    public static void sendToggleFilm(String filmId, boolean withCamera)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(filmId);
        buf.writeBoolean(withCamera);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_TOGGLE_FILM)));
    }

    public static void sendActionState(String filmId, ActionState state, int tick)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(filmId);
        buf.writeByte(state.ordinal());
        buf.writeInt(tick);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_ACTION_CONTROL)));
    }

    public static void sendSyncData(String filmId, BaseValue data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_FILM_DATA_SYNC, data.toData(), (packetByteBuf) ->
        {
            DataPath path = data.getPath();

            packetByteBuf.writeUtf(filmId);
            packetByteBuf.writeInt(path.strings.size());

            for (String string : path.strings)
            {
                packetByteBuf.writeUtf(string);
            }
        });
    }

    /** Ask the server to write the wand's region out. The reply drops the structure cache. */
    /** Save the region and empty it out of the world, for the film cut. */
    public static void sendCutStructure(String name, BlockPos from, BlockPos to)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(name);
        buf.writeBlockPos(from);
        buf.writeBlockPos(to);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_CUT_STRUCTURE)));
    }

    public static void sendSaveStructure(String name, BlockPos from, BlockPos to)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(name);
        buf.writeBlockPos(from);
        buf.writeBlockPos(to);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_SAVE_STRUCTURE)));
    }

    public static void sendTeleport(Player entity, double x, double y, double z)
    {
        sendTeleport(x, y, z, entity.getHeadYaw(), entity.getHeadYaw(), entity.getViewXRot());
    }

    public static void sendTeleport(double x, double y, double z, float yaw, float bodyYaw, float pitch)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeFloat(bodyYaw);
        buf.writeFloat(pitch);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_PLAYER_TP)));
    }

    public static void sendFormTrigger(String triggerId, int type)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(triggerId);
        buf.writeInt(type);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_ANIMATION_STATE_TRIGGER)));
    }

    public static void sendSharedForm(Form form, UUID uuid)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_SHARED_FORM, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {
            packetByteBuf.writeUUID(uuid);
        });
    }

    public static void sendZoom(boolean zoom)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeBoolean(zoom);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_ZOOM)));
    }

    public static void sendPauseFilm(String filmId)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();

        buf.writeUtf(filmId);

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_PAUSE_FILM)));
    }

    /**
     * Dress the player like the film's first person replay at given tick, and give them its
     * health, hunger and experience.
     */
    public static void sendApplyFilmPlayerSettingsToPlayer(Film film, int tick)
    {
        FriendlyByteBuf buf = PacketByteBufs.create();
        Replay replay = film.getFirstPersonReplay();

        buf.writeFloat(film.hp.get());
        buf.writeFloat(film.hunger.get());
        buf.writeInt(film.xpLevel.get());
        buf.writeFloat(film.xpProgress.get());
        buf.writeInt(replay == null ? 0 : replay.keyframes.getSelectedSlot(tick));

        if (replay == null)
        {
            buf.writeInt(0);
        }
        else
        {
            byte[] dressBytes = DataStorageUtils.writeToBytes(replay.keyframes.packEquipment(tick));

            buf.writeInt(dressBytes.length);
            buf.writeBytes(dressBytes);
        }

        ClientPlayNetworking.send(ServerNetwork.BufPayload.from(buf, ServerNetwork.idFor(ServerNetwork.SERVER_APPLY_FILM_PLAYER_SETTINGS)));
    }
}