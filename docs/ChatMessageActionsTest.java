package com.november.mcphone.feature.chat.client.messageaction;

import com.november.mcphone.feature.chat.ChatDeletionResult;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.client.contextmenu.ChatContextMenuView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 菜单几何、复制来源、待确认、超时与会话生命周期回归，不依赖图形上下文。 */
public final class ChatMessageActionsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private record Sent(UUID peer, UUID message, UUID request) {}
    public static void main(String[] args) {
        UUID peer = new UUID(0, 1), id = new UUID(0, 2);
        var whole = new ChatMessageTarget(peer, id, "中文 abc\n第二行", "");
        var selected = new ChatMessageTarget(peer, id, "中文 abc\n第二行", "abc\n第");
        var image = new ChatMessageTarget(peer, id, null, "");
        var menu = new ChatContextMenu();
        menu.open(whole, 10, 20);
        check(menu.entries(false).get(0).icon() == ChatContextMenuView.Icon.COPY, "文本菜单第一行复制");
        check(menu.entries(false).get(1).icon() == ChatContextMenuView.Icon.DELETE, "文本菜单第二行删除");
        menu.open(image, 10, 20);
        check(menu.entries(false).get(0).icon() == ChatContextMenuView.Icon.DELETE, "图片仅显示删除");
        check(menu.entries(false).size() == 1, "图片没有额外的假操作项");
        var copied = new ArrayList<String>(); var sent = new ArrayList<Sent>();
        var cancelled = new ArrayList<UUID>(); var notices = new ArrayList<String>();
        var actions = new ChatMessageActions((g,x,y,w,h) -> {}, copied::add,
                (p,m,r) -> sent.add(new Sent(p,m,r)), cancelled::add, notices::add);
        actions.execute(whole, ChatMessageAction.COPY, 100);
        actions.execute(selected, ChatMessageAction.COPY, 100);
        actions.execute(image, ChatMessageAction.COPY, 100);
        check(copied.equals(List.of(whole.fullText(), selected.selectedText())), "整条与选区复制保留原始换行，图片不写剪贴板");
        actions.open(peer,id,whole.fullText(),"",10,20);
        check(actions.mouseClicked(-100,-100,0,100) && !actions.isOpen() && sent.isEmpty(), "点菜单外只关闭，无穿透操作");
        actions.open(peer,id,whole.fullText(),"",10,20);
        check(!actions.mouseClicked(10,20,1,100) && !actions.isOpen(), "右键交回页面用于重新选择目标");
        actions.open(peer,id,whole.fullText(),"",10,20);
        actions.reconcile(List.of(new ChatMessage(id,peer,1,new com.november.mcphone.feature.chat.TextBody(whole.fullText()))));
        check(actions.isOpen(), "重同步新消息对象仍按 ID 保留菜单");
        actions.reconcile(List.of()); check(!actions.isOpen(), "目标被淘汰或删除时关闭菜单");
        actions.execute(selected, ChatMessageAction.DELETE, 100);
        check(sent.size() == 1 && sent.get(0).message().equals(id) && sent.get(0).peer().equals(peer), "删除针对完整消息 ID，不发送选区或所有者");
        actions.execute(whole,ChatMessageAction.DELETE,200); check(sent.size() == 1, "确认前不叠加删除请求");
        actions.result(new UUID(0,99),ChatDeletionResult.OK);
        actions.execute(whole,ChatMessageAction.DELETE,300); check(sent.size() == 1, "错误票据不能解除待确认");
        actions.tick(6099); check(notices.isEmpty(), "到期前不报超时");
        actions.tick(6100); actions.tick(7000);
        check(notices.size() == 1 && notices.get(0).endsWith("timeout") && cancelled.equals(List.of(sent.get(0).request())), "超时取消票据且只提示一次");
        actions.result(sent.get(0).request(),ChatDeletionResult.FORBIDDEN);
        check(notices.size() == 1, "超时后的迟到确认不污染下一次操作");
        for (var result : ChatDeletionResult.values()) {
            actions.execute(whole,ChatMessageAction.DELETE,10000);
            int size = notices.size(); actions.result(sent.get(sent.size()-1).request(),result);
            check(notices.size() == size + (result == ChatDeletionResult.OK ? 0 : 1), "成功静默、失败可反馈并解除待确认");
        }
        actions.execute(whole,ChatMessageAction.DELETE,20000);
        UUID request = sent.get(sent.size()-1).request(); actions.reset(); actions.tick(30000);
        check(cancelled.get(cancelled.size()-1).equals(request) && !actions.isOpen(), "关闭页面取消未完成票据");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
