package com.november.mcphone.network.notes;

import com.november.mcphone.PhoneItem;
import com.november.mcphone.notes.Note;
import com.november.mcphone.notes.NotePrinter;
import com.november.mcphone.notes.NoteService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 记事本相关网络包的注册与处理。
 *
 * 从 NetworkHandler 拆出来的理由与 ChatNetworking 一样：那边已经堆了
 * 壁纸、设备名、末影箱三组，聊天又是十来个，全塞一处会变成杂物间。
 *
 * 本类只做传输层的事——收包、校验来源、把结果发回去。真正的业务规则
 * 在 {@link NoteService}。
 */
public final class NotesNetworking {

    private NotesNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        // C2S: 打开记事本，请求列表
        registrar.playToServer(
                RequestNoteListPacket.TYPE,
                RequestNoteListPacket.STREAM_CODEC,
                NotesNetworking::handleRequestNoteList
        );

        // S2C: 下发列表（只有摘要）
        registrar.playToClient(
                SyncNoteListPacket.TYPE,
                SyncNoteListPacket.STREAM_CODEC,
                NotesNetworking::handleSyncNoteList
        );

        // C2S: 点进某条，请求全文
        registrar.playToServer(
                RequestNotePacket.TYPE,
                RequestNotePacket.STREAM_CODEC,
                NotesNetworking::handleRequestNote
        );

        // S2C: 下发某条的全文
        registrar.playToClient(
                SyncNotePacket.TYPE,
                SyncNotePacket.STREAM_CODEC,
                NotesNetworking::handleSyncNote
        );

        // C2S: 保存一条
        registrar.playToServer(
                SaveNotePacket.TYPE,
                SaveNotePacket.STREAM_CODEC,
                NotesNetworking::handleSaveNote
        );

        // C2S: 删掉一条
        registrar.playToServer(
                DeleteNotePacket.TYPE,
                DeleteNotePacket.STREAM_CODEC,
                NotesNetworking::handleDeleteNote
        );

        // C2S: 印成一本书
        registrar.playToServer(
                PrintNotePacket.TYPE,
                PrintNotePacket.STREAM_CODEC,
                NotesNetworking::handlePrintNote
        );
    }

    // ============================================================
    //  服务端侧
    // ============================================================

    private static void handleRequestNoteList(RequestNoteListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /**
     * 客户端要某条的全文。
     *
     * 笔记不存在时回一条正文为空的：可能是玩家在别处删掉了它，界面收到
     * 空正文自会退回列表。为此单设一个"没找到"的包不值得。
     */
    private static void handleRequestNote(RequestNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            Note note = NoteService.getNote(player, packet.id())
                    .orElseGet(() -> new Note(packet.id(), "", 0L));
            ctx.reply(new SyncNotePacket(note));
        });
    }

    /**
     * 客户端要保存。
     *
     * 校验全在 NoteService，这里只负责把结果送出去。
     *
     * 无论成没成都回发一次列表：成了要让客户端拿到服务端分配的 id 与
     * 新的排序；没成（条数满了、校验没过）则让客户端的列表回到真值，
     * 免得界面上留着一条并不存在的笔记。
     */
    private static void handleSaveNote(SaveNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            NoteService.saveNote(player, packet.id(), packet.body());
            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /** 客户端要删除。同样无论成败都回发列表，理由见上 */
    private static void handleDeleteNote(DeleteNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            NoteService.deleteNote(player, packet.id());
            ctx.reply(new SyncNoteListPacket(NoteService.buildSummaries(player)));
        });
    }

    /**
     * 客户端要把一条笔记印成书。
     *
     * 正文取服务端存的那份，不采信包里的内容——否则改个客户端就能印出
     * 任意内容的书。
     *
     * 结果用动作栏告诉玩家：成了说印好了，没成说缺一本空白的书与笔。
     * 静默失败最糟——玩家会以为按钮坏了，反复去点。
     */
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

    // ============================================================
    //  客户端侧
    // ============================================================

    private static void handleSyncNoteList(SyncNoteListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NotesClientCache.setSummaries(packet.notes()));
    }

    private static void handleSyncNote(SyncNotePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NotesClientCache.setOpenNote(packet.note()));
    }
}
