package com.november.mcphone.platform.client.port;

import net.minecraft.client.input.CharacterEvent;
import java.util.ArrayList;
import java.util.List;

/** 使用实际 CharacterEvent，确认原生控件不会把一个补充字符提交两次。 */
public final class PhoneNativeCharacterTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        CharacterEvent previous = PhoneInputs.character;
        try {
            List<Integer> delivered = new ArrayList<>();
            PhoneInputs.character = new CharacterEvent(0x1f680, 0);
            boolean consumed = PhoneInputs.dispatchCharacter(0x1f680, unit -> {
                delivered.add(PhoneInputs.character((char) unit).codepoint());
                return true;
            });
            check(consumed && delivered.equals(List.of(0x1f680)), "原生控件收到一次完整码点");
            check(PhoneInputs.character.codepoint() == 0x1f680, "事件上下文保持原样");
            delivered.clear();
            com.november.mcphone.api.client.ui.IPhonePage legacyPage = new com.november.mcphone.api.client.ui.IPhonePage() {
                @Override public void render(com.november.mcphone.api.client.ui.PhoneCanvas canvas) {}
                @Override public boolean charTyped(char unit, int modifiers) {
                    return PhoneInputs.submitCharacter(unit, event -> { delivered.add(event.codepoint()); return true; });
                }
            };
            legacyPage.charTyped(0x1f680, 0);
            check(delivered.equals(List.of(0x1f680)), "旧附属代理对转发给原生控件时不重复插入");
            delivered.clear();
            PhoneInputs.character = new CharacterEvent(0x1de80, 0);
            PhoneInputs.dispatchCharacter(0x1de80, unit -> PhoneInputs.submitCharacter((char) unit,
                    event -> { delivered.add(event.codepoint()); return true; }));
            check(delivered.equals(List.of(0x1de80)), "低16位落在代理区的码点也能提交一次");

            PhoneInputs.character = null;
            StringBuilder legacy = new StringBuilder();
            check(PhoneInputs.dispatchCharacter(0x1f680, unit -> { legacy.append((char) unit); return true; }), "旧回调消费结果保留");
            check(legacy.toString().equals("\ud83d\ude80"), "没有原生事件时完整转发代理对");
            int[] calls = {0};
            PhoneInputs.dispatchCharacter('中', unit -> { calls[0]++; return true; });
            check(calls[0] == 1, "BMP 字符只回调一次");
            check(!PhoneInputs.dispatchCharacter(-1, unit -> { throw new AssertionError("invalid"); }), "拒绝非法码点");
            System.out.println("PhoneNativeCharacterTest passed: " + checks + " assertions");
        } finally {
            PhoneInputs.character = previous;
        }
    }
}
