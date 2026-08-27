package com.november.mcphone.feature.reader;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 书架上的一本书 —— 界面认得的全部信息。
 *
 * 这里【不出现】任何外部模组的类型
 *
 * 书架页只认这个记录：它不知道这本书是 Patchouli 的、是别的手册模组的、
 * 还是玩家自己传进来的。要打开它、要画它的图标，都回头去问它的书源
 * （{@link com.november.mcphone.feature.reader.client.source.BookSource}）。
 *
 * 这条界线是「多接一种书」这件事只用改一个文件的原因，也是类型隔离的兑现方式：
 * vazkii.* 的类只在 PatchouliSource 的方法体里出现，界面这一层引用不到，
 * 对方没装时也就不可能在这里炸出 NoClassDefFoundError。
 *
 * 为什么标题存 Component 而不是 String
 *
 * 书名与副标题都是翻译键。存成 Component 是【懒】的：玩家中途换语言，
 * 下次画出来就是新语言。存成 String 就等于把扫描那一刻的语言腌进缓存里。
 *
 * @param sourceId  哪个书源给的，与 {@link com.november.mcphone.feature.reader.client.source.BookSource#id()} 一致。
 *                  打开时靠它找回书源，所以别改已发布的取值
 * @param bookId    书在它自己那套体系里的 id，如 {@code ars_nouveau:worn_notebook}
 * @param title     书名
 * @param subtitle  副标题，没有则 null。眼下不画，留给详情与搜索
 * @param owner     出自哪个模组，给玩家看的名字。列表第二行就是它——
 *                  几百个模组的整合包里，"这本书是谁的"比副标题有用得多
 */
public record BookRef(String sourceId, ResourceLocation bookId,
                      Component title, Component subtitle, String owner) {
}
