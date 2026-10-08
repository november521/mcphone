package com.november.mcphone.feature.chat.client;

/** 手机、平板和中英文按钮的布局边界：缩小字体后不能挤出输入框，也不能因取整让正文越界。 */
public class ChatLayoutTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!ChatLayout.hasText(null), "尚未创建输入框");
        check(!ChatLayout.hasText(""), "空输入隐藏发送");
        check(!ChatLayout.hasText(" \t\n\u3000"), "全空白输入隐藏发送");
        check(ChatLayout.hasText("1"), "数字显示发送");
        check(ChatLayout.hasText(" 中文 "), "中文显示发送");
        for (int width : new int[]{112, 232}) {
            for (int labelWidth : new int[]{11, 14, 15, 18, 30}) {
                for (boolean hasText : new boolean[]{false, true}) {
                    var c = ChatLayout.composer(width, labelWidth, hasText);
                    check(c.inputWidth() >= 40, "输入框需容纳正常文本");
                    check(c.inputX() >= ChatLayout.ICON_SIZE + 2, "语音不能覆盖输入框");
                    check(c.inputX() + c.inputWidth() < c.stickerX(), "表情不能覆盖输入框");
                    check(c.stickerX() + ChatLayout.ICON_SIZE < c.actionX(), "发送不能覆盖表情");
                    check(c.actionX() + c.actionWidth() <= width, "右侧动作不能越界");
                    if (hasText) {
                        check(c.actionWidth() >= labelWidth + 4, "中英文发送文字都有内边距");
                    } else {
                        check(c.actionWidth() == ChatLayout.ICON_SIZE, "空白时不为加号预留发送按钮宽度");
                    }
                    check(c.actionX() - c.stickerX() - ChatLayout.ICON_SIZE == 2, "表情与右侧动作保持紧凑间距");
                    // 控件左右各扩一像素后会相接，整条边界只能命中一个控件。
                    int boundary = c.actionX() - 1;
                    check(!ChatLayout.hit(boundary, 8, c.stickerX() - 1, 0,
                            ChatLayout.ICON_SIZE + 2, ChatLayout.INPUT_HEIGHT), "表情右边界不能吞掉加号的点击");
                    check(ChatLayout.hit(boundary, 8, c.actionX() - 1, 0,
                            c.actionWidth() + 2, ChatLayout.INPUT_HEIGHT), "右侧动作左边界仍能点到");
                }
            }
        }
        check(ChatLayout.TAB_ICON_TOP + ChatLayout.ICON_SIZE < ChatLayout.TAB_LABEL_TOP,
                "栏目图标不能压住文字");
        check(ChatLayout.TAB_LABEL_TOP + ChatLayout.scaledWidth(9, ChatLayout.TAB_LABEL_SCALE)
                <= ChatLayout.TAB_HEIGHT, "小字号栏目文字不能越出底部栏");
        check(ChatLayout.SEND_HEIGHT <= ChatLayout.INPUT_HEIGHT, "发送按钮不得超过输入栏高度");
        int cardH = ChatLayout.cardHeight(ChatLayout.scaledWidth(9, ChatLayout.TEXT_SCALE),
                ChatLayout.scaledWidth(9, ChatLayout.META_SCALE));
        check(cardH - ChatLayout.CARD_PAD * 2 >= ChatLayout.AVATAR_SIZE + ChatLayout.AVATAR_FRAME * 2,
                "头像边框不能越出卡片");
        check(cardH - ChatLayout.CARD_PAD * 2 >= 7 + 1 + Math.max(6, ChatLayout.UNREAD_HEIGHT),
                "名称、单行摘要与未读数需要完整行高");
        check(cardH == 20, "普通字号的单行摘要卡片收紧到二十逻辑像素");
        check(ChatLayout.visibleCards(128, cardH) == 5, "手机正常字号可完整显示五张会话卡片");
        for (int available = cardH; available <= 232; available++) {
            int count = ChatLayout.visibleCards(available, cardH);
            check(count * cardH + (count - 1) * ChatLayout.CARD_GAP <= available,
                    "最后一张卡片不得压住栏目栏");
            check((count + 1) * cardH + count * ChatLayout.CARD_GAP > available,
                    "卡片完整容纳时不能提前隐藏");
        }
        check(!ChatLayout.hit(4, cardH, 0, 0, 104, cardH), "卡片间隙不能打开前一条会话");
        check(!ChatLayout.hit(4, cardH, 0, cardH + ChatLayout.CARD_GAP, 104, cardH),
                "卡片间隙不能打开后一条会话");
        check(ChatLayout.SEARCH_ICON_SIZE <= ChatLayout.scaledWidth(9, ChatLayout.META_SCALE),
                "搜索图标不能比搜索文字行更大");
        check(ChatLayout.SEARCH_TEXT_X > ChatLayout.SEARCH_PAD_X + ChatLayout.SEARCH_ICON_SIZE,
                "放大镜不能挤到搜索文字");
        for (int width : new int[]{112, 232}) {
            for (int[] metrics : new int[][]{{7, 6}, {9, 8}}) {
                int nameH = metrics[0], previewH = metrics[1];
                for (int timeW : new int[]{0, 18, 24}) {
                    for (int unreadW : new int[]{0, 10, 14, 18}) {
                        for (boolean canTeleport : new boolean[]{false, true}) {
                            var card = ChatLayout.card(width, nameH, previewH, timeW, unreadW, canTeleport);
                            var avatar = card.avatarFrame();
                            check(avatar.x() >= 0 && avatar.x() + avatar.width() <= width, "头像框水平不越界");
                            check(avatar.y() >= 0 && avatar.y() + avatar.height() <= card.height(), "头像框纵向不越界");
                            check(card.nameWidth() > 0 && card.previewWidth() > 0, "图标不能挤掉全部名称或摘要");
                            check(card.textX() >= avatar.x() + avatar.width() + ChatLayout.AVATAR_TEXT_GAP,
                                    "名称与头像保留间距");
                            check(card.previewY() >= card.nameY() + nameH + 1, "单行摘要不能压住名称");
                            check(card.previewY() + previewH <= card.height() - ChatLayout.CARD_PAD,
                                    "摘要不能越出卡片底边");
                            check(Math.abs(card.timeY() + previewH / 2f - card.nameY() - nameH / 2f) < .001f,
                                    "时间与名称垂直居中对齐");
                            check(card.textX() + card.nameWidth() <= (timeW > 0
                                    ? card.timeX() - ChatLayout.CARD_ACTION_GAP : width - ChatLayout.CARD_PAD),
                                    "长名称不得挤到时间");
                            float footerCenter = card.previewY() + previewH / 2f;
                            if (unreadW > 0) {
                                var badge = card.unread();
                                check(Math.abs(footerCenter - badge.y() - badge.height() / 2f) < .001f,
                                        "未读数与摘要共用底行中心");
                                check(badge.x() + badge.width() <= width - ChatLayout.CARD_PAD, "未读数右侧不越界");
                                check(card.textX() + card.previewWidth() <= badge.x() - ChatLayout.CARD_ACTION_GAP,
                                        "摘要不能覆盖未读数");
                            }
                            if (canTeleport) {
                                var hit = card.teleportHit();
                                check(Math.abs(footerCenter - card.teleportY() - ChatLayout.TELEPORT_ICON_SIZE / 2f) < .001f,
                                        "传送图标与摘要共用底行中心");
                                check(card.teleportX() >= hit.x()
                                        && card.teleportX() + ChatLayout.TELEPORT_ICON_SIZE <= hit.x() + hit.width(),
                                        "传送图标在点击区内");
                                check(card.teleportY() >= hit.y()
                                        && card.teleportY() + ChatLayout.TELEPORT_ICON_SIZE <= hit.y() + hit.height(),
                                        "传送图标纵向在点击区内");
                                check(hit.y() > card.nameY() + nameH, "传送点击区不得覆盖名称行");
                                check(hit.y() + hit.height() <= card.height() - ChatLayout.CARD_PAD,
                                        "传送点击区不能溢出卡片");
                                check(card.textX() + card.previewWidth() <= hit.x() - ChatLayout.CARD_ACTION_GAP,
                                        "摘要不能伸到传送点击区");
                                check(hit.x() + hit.width() <= (unreadW > 0
                                        ? card.unread().x() - ChatLayout.CARD_ACTION_GAP : width - ChatLayout.CARD_PAD),
                                        "传送与未读数不能重叠");
                                check(hit.contains(card.teleportX() + 3.5, footerCenter), "传送中心可点击");
                                check(!hit.contains(card.teleportX() + 3.5, card.nameY() + nameH - .1),
                                        "点名称不能触发传送");
                                check(!hit.contains(hit.x() + hit.width(), footerCenter), "传送右边界不吞掉相邻控件");
                            } else {
                                check(!card.teleportHit().contains(0, 0), "禁止传送时没有活动点击区");
                            }
                        }
                    }
                }
            }
        }
        for (int nameHeight : new int[]{5, 6, 8}) {
            for (int bodyHeight : new int[]{1, 8, 11, 12, 18, 56, 140}) {
                var row = ChatLayout.messageRow(bodyHeight, nameHeight);
                check(row.contentY() > nameHeight, "气泡顶部需给未来昵称留出空间");
                check(row.avatarY() > nameHeight, "头像行不覆盖昵称槽");
                check(row.contentY() + bodyHeight <= row.height(), "气泡和图片不超出消息行");
                check(row.avatarY() + ChatLayout.AVATAR_SIZE <= row.height(), "头像不超出消息行");
                if (bodyHeight <= ChatLayout.AVATAR_SIZE) {
                    check(row.contentY() + bodyHeight == row.avatarY() + ChatLayout.AVATAR_SIZE,
                            "短气泡及小图片的底边应与头像底边对齐");
                } else {
                    check(row.contentY() == row.avatarY(), "长消息从头像行向下延伸");
                }
            }
        }
        for (float scale : new float[]{ChatLayout.TEXT_SCALE, ChatLayout.META_SCALE, ChatLayout.TAB_LABEL_SCALE}) {
            for (int width = 6; width <= 232; width++) {
                int nativeWidth = ChatLayout.unscaledWidth(width, scale);
                check(ChatLayout.scaledWidth(nativeWidth, scale) <= width, "换行后正文不能越界");
            }
        }
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
