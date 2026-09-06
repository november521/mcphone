package com.november.mcphone.feature.notes.net;

import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NotePrinter;
import com.november.mcphone.feature.notes.NoteService;
import com.november.mcphone.core.net.RequestThrottle;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 记事本网络包（服务端一半）。只做传输层的事，业务规则在 {@link NoteService}。
 * S2C 客户端接收在 {@code feature/notes/client/NotesNetworkingClient}。
 */
public final class NotesNetworking {

    private NotesNetworking() {}

    /** 由 NetworkHandler.registerServer 调用 */
    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(RequestNoteListPacket.TYPE, RequestNoteListPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestNoteListPacket.TYPE, NotesNetworking::handleRequestNoteList);

        PayloadTypeRegistry.playC2S().register(RequestNotePacket.TYPE, RequestNotePacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestNotePacket.TYPE, NotesNetworking::handleRequestNote);

        PayloadTypeRegistry.playC2S().register(SaveNotePacket.TYPE, SaveNotePacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SaveNotePacket.TYPE, NotesNetworking::handleSaveNote);

        PayloadTypeRegistry.playC2S().register(DeleteNotePacket.TYPE, DeleteNotePacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DeleteNotePacket.TYPE, NotesNetworking::handleDeleteNote);

        PayloadTypeRegistry.playC2S().register(PrintNotePacket.TYPE, PrintNotePacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PrintNotePacket.TYPE, NotesNetworking::handlePrintNote);
    }

    private static void handleRequestNoteList(RequestNoteListPacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE_LIST)) return;

        ServerPlayNetworking.send(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 笔记不存在时回一条正文为空的，界面收到后自会退回列表 */
    private static void handleRequestNote(RequestNotePacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE)) return;

        Note note = NoteService.getNote(player, packet.id())
                .orElseGet(() -> new Note(packet.id(), "", 0L));
        ServerPlayNetworking.send(player, new SyncNotePacket(note));
    }

    /** 无论成败都回发列表：成了让客户端拿到服务端分配的 id，没成让列表回到真值 */
    private static void handleSaveNote(SaveNotePacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();

        NoteService.saveNote(player, packet.id(), packet.body());
        ServerPlayNetworking.send(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 同样无论成败都回发列表 */
    private static void handleDeleteNote(DeleteNotePacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();

        NoteService.deleteNote(player, packet.id());
        ServerPlayNetworking.send(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 正文取服务端存的那份，不采信包里的内容；结果用动作栏告知玩家 */
    private static void handlePrintNote(PrintNotePacket packet, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        if (!PhoneItem.isCarriedBy(player)) return;

        boolean done = NoteService.getNote(player, packet.id())
                .map(note -> NotePrinter.print(player, note))
                .orElse(false);

        player.displayClientMessage(Component.translatable(
                done ? "mcphone.notes.print_done" : "mcphone.notes.print_failed"), true);
    }
}
