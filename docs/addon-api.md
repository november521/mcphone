# 给附属模组开发者：做一个手机 App

实现 `IPhoneApp`，用 Java SPI 注册，MCphone 会自动发现并加载。

> 这份文档原先整篇写在 `IPhoneApp` 的 javadoc 里。教程不该占着接口的
> 契约说明，所以搬到这儿；接口里只留"这个方法承诺什么"。

MCphone App API —— 开放的 App 接口。

 【附属模组开发者指南 —— 像安装手机 App 一样开发】

 你的模组只需要依赖 MCphone，实现这个接口，然后通过
 Java SPI（META-INF/services）注册。MCphone 会自动发现并加载。

 动手之前：这个接口是【客户端专用】的

 本接口的 renderIcon 签名里有 GuiGraphics，实现类只能在客户端加载。
 把它放进你自己的公共包、再被物品或网络包顺带引用到，专用服务器会
 启动即崩，而且崩溃信息不会提到你的 App。详见本包的 package-info。

 建议：实现类放 yourmod.client 包下，只由客户端代码碰它。

 第一步：在你的 build.gradle 添加依赖

 MCphone 是 NeoForge 模组，用 ModDevGradle（不是 ForgeGradle，
 没有 fg.deobf 这种东西）：

   dependencies {
       // 坐标 = mod_group_id : mod_id : 版本
       compileOnly "com.november.mcphone:mcphone:$版本"
       // 想在开发环境里真跑起来，再加一行
       localRuntime "com.november.mcphone:mcphone:$版本"
   }

 版本填你要对接的那一版，别照抄这里的占位符。MCphone 目前没有公开
 maven，最省事的办法是把 jar 丢进你项目的 libs/ 然后：

   dependencies {
       compileOnly files("libs/mcphone-$版本.jar")
   }

 再在你的 neoforge.mods.toml 里声明依赖，让加载顺序正确：

   [[dependencies.yourmod]]
       modId="mcphone"
       type="required"        # 可选依赖写 "optional"
       versionRange="[1.0.47,)"
       ordering="AFTER"       # 必须 AFTER：你要用的注册表得先就位
       side="BOTH"

 第二步：实现 IPhoneApp

   public final class CalculatorApp implements IPhoneApp {

       @Override
       public ResourceLocation getId() {
           return ResourceLocation.fromNamespaceAndPath("mymod", "calculator");
       }

       @Override
       public Component getDisplayName() {
           return Component.translatable("mymod.app.calculator");
       }

       @Override
       public ResourceLocation getIconTexture() {
           return ResourceLocation.fromNamespaceAndPath(
                   "mymod", "textures/app/calculator.png");
       }

       @Override
       public void onPress() {
           Minecraft.getInstance().setScreen(new CalculatorScreen());
       }
   }

 别继承 MCphone 内建的 PhoneApp 基类——它把命名空间写死成 mcphone，
 是给内建 App 用的。直接实现本接口。

 第三步：注册（SPI 自动发现）

 在 src/main/resources/META-INF/services/ 创建文件：

   文件名: com.november.mcphone.api.client.app.IPhoneApp
   内容:   com.yourmod.CalculatorApp

 如果有多个 App，一行一个类名。

 第四步：语言文件 + 贴图

   语言: assets/mymod/lang/en_us.json
         { "mymod.app.calculator": "Calculator" }
         中文另开一份 zh_cn.json，两边键要对齐

   贴图: assets/mymod/textures/app/calculator.png
         20×20、PNG-32。路径要和 getIconTexture() 返回的一致。
         不放贴图也能跑：renderIcon 的默认实现在贴图缺失时由原版画成
         紫黑格，不会崩，但玩家会看见紫黑格，所以还是放一张。

 还有什么可用

 应用商店来源  {@link com.november.mcphone.api.client.store.IAppSource}
               让你的 App 从别处（远程仓库、数据包）进入商店列表

 代价          {@link com.november.mcphone.api.cost.ICost}
               让某个操作要求玩家先付出点什么。这个是两端安全的，
               服务端代码可以放心引用

 条件登记      {@link #isAvailable()}
               你的 App 依赖另一个可选模组时覆盖它。对方没装，你的 App
               就不进目录，主屏与应用商店里都不会出现

 联动模组      {@link #companionMods()}
               你的 App 沾了另一个模组的光 —— 无论是"装了多一块内容"，还是
               "没装这个 App 就没内容可给"。两种都算联动：MCphone 自己一个
               前置都没有，缺了谁都照常开机，只是少那一样东西。

               声明后会出现在「设置 → 关于」的联动模组列表里，告诉玩家装没装；
               对方没装而你的 App 因此不可用时，商店的「联动App」页也会列出它、
               写明缺的是哪个模组（那一页对当前不可用的 App 会回退来读这里）。

               ⚠ 属于后一种（没装就没内容可给）时，必须自己覆盖
               {@link #isAvailable()}。联动声明【不】参与默认的可用性判断，
               照默认走就是"永远可用"，主屏上会多一个点了没反应的图标。
               内建的「任务书」就是这么写的，可以照抄

 角标          {@link #getBadgeCount()}
               主屏图标右上角的那个数，像未读红点。每帧每个图标问一次，
               只能读现成的值——要现拉数据就自己按时间间隔限流
