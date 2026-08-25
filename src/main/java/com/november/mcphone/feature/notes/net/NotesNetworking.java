package com.november.mcphone.feature.notes.net;

import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NotePrinter;
import com.november.mcphone.feature.notes.NoteService;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 记事本网络包的注册与处理；只做传输层的事，业务规则在 {@link NoteService} */
public final class NotesNetworking {

    private NotesNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                RequestNoteListPacket.TYPE,
                RequestNoteListPacket.STREAM_CODEC,
                NotesNetworking::handleRequestNoteList
        );

        registrar.playToClient(
                SyncNoteListPacket.TYPE,
                SyncNoteListPacket.STREAM_CODEC,
                NotesNetworking::handleSyncNoteList
        );

        registrar.playToServer(
                RequestNotePacket.TYPE,
                RequestNotePacket.STREAM_CODEC,
                NotesNetworking::handleRequestNote
        );

        registrar.playToClient(
                SyncNotePacket.TYPE,
                SyncNotePacket.STREAM_CODEC,
                NotesNetworking::handleSyncNote
        );

        registrar.playToServer(
                SaveNotePacket.TYPE,
                SaveNotePacket.STREAM_CODEC,
                NotesNetworking::handleSaveNote
        );

        registrar.playToServer(
                DeleteNotePacket.TYPE,
                DeleteNotePacket.STREAM_CODEC,
                NotesNetworking::handleDeleteNote
        );

        registrar.playToServer(
                PrintNotePacket.TYPE,
                PrintNotePacket.STREAM_CODEC,
                NotesNetworking::handlePrintNote
        );
    }

    private static void handleRequestNoteList(RequestNoteListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE_LIST)) return;

            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /** 笔记不存在时回一条正文为空的，界面收到后自会退回列表 */
    private static void handleRequestNote(RequestNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE)) return;

            Note note = NoteService.getNote(player, packet.id())
                    .orElseGet(() -> new Note(packet.id(), "", 0L));
            ctx.reply(new SyncNotePacket(note));
        });
    }

    /** 无论成败都回发列表：成了让客户端拿到服务端分配的 id，没成让列表回到真值 */
    private static void handleSaveNote(SaveNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            NoteService.saveNote(player, packet.id(), packet.body());
            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /** 同样无论成败都回发列表 */
    private static void handleDeleteNote(DeleteNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            NoteService.deleteNote(player, packet.id());
            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /** 正文取服务端存的那份，不采信包里的内容；结果用动作栏告知玩家 */
    private static void handlePrintNote(PrintNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!PhoneItem.isCarriedBy(player)) return;

            boolean done = NoteService.getNote(player, packet.id())
                    .map(note -> NotePrinter.print(player, note))
                    .orElse(false);

            player.displayClientMessage(Component.translatable(
                    done ? "mcphone.notes.print_done" : "mcphone.notes.print_failed"), true);
        });
    }

    private static void handleSyncNoteList(SyncNoteListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NotesClientCache.setSummaries(packet.notes()));
    }

    private static void handleSyncNote(SyncNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NotesClientCache.setOpenNote(packet.note()));
    }
}
