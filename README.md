# MCphone

把一部能用的智能手机塞进 Minecraft —— 拍照、翻相册、换壁纸、听歌、装 App，还能给自己那一部手机起个名。

**Minecraft 1.21.1** · **NeoForge 21.1.200+** · 客户端与服务端都需安装

---

## 怎么拿到手机

工作台合成，一次一部：

```
玻璃   玻璃   玻璃
红染料 黄染料 蓝染料
铁锭   红石   铁锭
```

拿在手上右键开机，点机身外的区域关机。

## 功能

| App | 说明 |
| --- | --- |
| 📷 相机 | 取景框 + 分帧截图。默认 **V** 拍照、**X** 退出，可在原版「选项 → 按键设置 → MCphone」改键。照片存进游戏目录的 `screenshots/` |
| 🖼 相册 | 缩略图网格、分页、大图查看、左右切换、删除（二次确认）。缩略图按需生成并缓存 |
| 🎵 音乐 | 播放原版唱片音乐（`JukeboxSong` 注册表），以及 `config/mcphone/music/` 下的 WAV 文件 |
| 🏪 应用商店 | 安装 / 卸载 App，安装状态持久化；系统 App 标灰不可卸载 |
| ⚙️ 设置 | 更换壁纸、设备命名、App 管理器 |
| 💬 消息 / 联系人 | 尚未实现，当前为占位 |

**自定义壁纸**：把任意尺寸的 PNG 放进 `config/mcphone/wallpapers/`，在「设置 → 更换壁纸」里选。壁纸选择由服务端保存并同步，多人游戏中每位玩家的壁纸各自独立。

**设备名称**：在「设置 → 设备名称」里起名，起过名的手机在物品栏显示该名，上限 24 字符，服务端做校验与截断。铁砧改名优先级更高，会盖过设备名。

**关于中文输入**：手机里的输入框用的就是原版按 T 那个聊天框的同一个控件，能不能打中文与原版完全一致。要注意的是 Minecraft 不会把输入法候选窗定位到光标处（GLFW 缺少相应接口，原版聊天框也一样），打拼音时基本看不见候选列表，等于盲打——想稳妥就用 **Ctrl+V** 粘贴。

---

## 给附属模组作者

App 系统通过 SPI 开放，你的模组不需要被 MCphone 感知也能往手机里装 App。内建 App 走的是同一套机制，没有走后门。

**注册一个 App**：实现 `com.november.mcphone.api.IPhoneApp`，在 `META-INF/services/com.november.mcphone.api.IPhoneApp` 中登记实现类，App 即自动出现。

**自定义商店来源**：实现 `com.november.mcphone.api.store.IAppSource`，用 `AppInfo` 描述可下载的 App，在 `META-INF/services/com.november.mcphone.api.store.IAppSource` 中登记。

---

## 从源码构建

```bash
./gradlew build
```

产物在 `build/libs/`。开发环境用 `./gradlew runClient` 启动。

映射使用 Mojang 官方名 + Parchment，其许可见 <https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>。

## 许可

All Rights Reserved（见 `gradle.properties` 的 `mod_license`）。
