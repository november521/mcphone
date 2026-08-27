package com.november.mcphone.feature.notes.net;

import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NotePrinter;
import com.november.mcphone.feature.notes.NoteService;
import com.november.mcphone.core.net.RequestThrottle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 记事本网络包的注册与处理；只做传输层的事，业务规则在 {@link NoteService} */
public final class NotesNetworking {

    private NotesNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register() {
        MCphoneNetwork.registerToServer(
                RequestNoteListPacket.class,
                RequestNoteListPacket::encode,
                RequestNoteListPacket::decode,
                NotesNetworking::handleRequestNoteList
        );

        MCphoneNetwork.registerToClient(
                SyncNoteListPacket.class,
                SyncNoteListPacket::encode,
                SyncNoteListPacket::decode,
                NotesNetworking::handleSyncNoteList
        );

        MCphoneNetwork.registerToServer(
                RequestNotePacket.class,
                RequestNotePacket::encode,
                RequestNotePacket::decode,
                NotesNetworking::handleRequestNote
        );

        MCphoneNetwork.registerToClient(
                SyncNotePacket.class,
                SyncNotePacket::encode,
                SyncNotePacket::decode,
                NotesNetworking::handleSyncNote
        );

        MCphoneNetwork.registerToServer(
                SaveNotePacket.class,
                SaveNotePacket::encode,
                SaveNotePacket::decode,
                NotesNetworking::handleSaveNote
        );

        MCphoneNetwork.registerToServer(
                DeleteNotePacket.class,
                DeleteNotePacket::encode,
                DeleteNotePacket::decode,
                NotesNetworking::handleDeleteNote
        );

        MCphoneNetwork.registerToServer(
                PrintNotePacket.class,
                PrintNotePacket::encode,
                PrintNotePacket::decode,
                NotesNetworking::handlePrintNote
        );
    }

    private static void handleRequestNoteList(RequestNoteListPacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE_LIST)) return;

        MCphoneNetwork.sendToPlayer(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 笔记不存在时回一条正文为空的，界面收到后自会退回列表 */
    private static void handleRequestNote(RequestNotePacket packet, ServerPlayer player) {
        if (!RequestThrottle.allow(player, RequestThrottle.Kind.NOTE)) return;

        Note note = NoteService.getNote(player, packet.id())
                .orElseGet(() -> new Note(packet.id(), "", 0L));
        MCphoneNetwork.sendToPlayer(player, new SyncNotePacket(note));
    }

    /** 无论成败都回发列表：成了让客户端拿到服务端分配的 id，没成让列表回到真值 */
    private static void handleSaveNote(SaveNotePacket packet, ServerPlayer player) {
        NoteService.saveNote(player, packet.id(), packet.body());
        MCphoneNetwork.sendToPlayer(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 同样无论成败都回发列表 */
    private static void handleDeleteNote(DeleteNotePacket packet, ServerPlayer player) {
        NoteService.deleteNote(player, packet.id());
        MCphoneNetwork.sendToPlayer(player, new SyncNoteListPacket(NoteService.buildSummaries(player)));
    }

    /** 正文取服务端存的那份，不采信包里的内容；结果用动作栏告知玩家 */
    private static void handlePrintNote(PrintNotePacket packet, ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return;

        boolean done = NoteService.getNote(player, packet.id())
                .map(note -> NotePrinter.print(player, note))
                .orElse(false);

        player.displayClientMessage(Component.translatable(
                done ? "mcphone.notes.print_done" : "mcphone.notes.print_failed"), true);
    }

    private static void handleSyncNoteList(SyncNoteListPacket packet) {
        NotesClientCache.setSummaries(packet.notes());
    }

    private static void handleSyncNote(SyncNotePacket packet) {
        NotesClientCache.setOpenNote(packet.note());
    }
}
