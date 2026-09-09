package com.november.mcphone.core;

/**
 * 这台设备是手机还是平板。
 *
 * <h2>为什么是一个枚举，而不是两个 Item 类</h2>
 *
 * 平板与手机在<b>玩法上是同一台机器</b>：同一批 App、同一份聊天记录、同一个终端卡槽，
 * 连数据都存在同一处（笔记、壁纸、买过的 App 全在玩家身上，见 {@code PhonePlayerData}）。
 * 两者只差两样东西 —— <b>物品模型</b>与<b>屏幕多大</b>。
 *
 * 为这点差别开一个 {@code TabletItem extends PhoneItem}，代价是每一处
 * {@code instanceof PhoneItem} 的判断都要重新想一遍"子类算不算"，而答案永远是"算"。
 * 所以物品类还是同一个 {@link PhoneItem}，它拿着一个本枚举的值；判断"这是不是本模组的
 * 设备"仍然只有 {@link PhoneItem#isDevice} 一处，加平板不需要动它。
 *
 * <h2>屏幕尺寸为什么不在这里</h2>
 *
 * 那是客户端的事：{@code core.client.DeviceMetrics} 按这个枚举给出屏幕宽高。本类要在
 * <b>专用服务器</b>上跟着物品一起加载，碰不得任何客户端类型（见 build.gradle 的 dist
 * 隔离校验），也不该知道界面长什么样。
 */
public enum DeviceKind {

    /** 手机：竖屏，右键开机 */
    PHONE("mcphone.item.tooltip.open"),

    /** 平板：横屏，屏幕更大，能一眼看到更多东西 */
    TABLET("mcphone.item.tooltip.open_tablet");

    private final String openTooltipKey;

    DeviceKind(String openTooltipKey) {
        this.openTooltipKey = openTooltipKey;
    }

    /**
     * 物品说明第一行的翻译键 —— "右键打开手机" / "右键打开平板"。
     *
     * 键放在枚举上而不是在 {@link PhoneItem} 里拼字符串：拼出来的键漏翻译时不会报错，
     * 玩家看到的是一行原始键名，而这儿写死的两个键，谁少了一条语言文件一眼就能对出来。
     */
    public String openTooltipKey() {
        return openTooltipKey;
    }
}
