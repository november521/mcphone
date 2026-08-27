# MCphone — Forge 1.20.1

**这一支还不能用。** 它现在是一副能编过、能装进游戏、能从创造栏里拿到手机物品的骨架，
手机的界面、App、网络层、联动一样都还没有。

要能用的版本请去 [`MCphone Neoforge 1.21.1/`](../MCphone%20Neoforge%201.21.1/)——
那是功能完整的正式版，Minecraft 1.21.1 + NeoForge。

## 现在有什么

- 一个能 `./gradlew build` 通过的 ForgeGradle 6 工程
- 手机物品：能合成（配方与 1.21.1 那支完全一致）、能拿在手里、有中英文名与图标
- 创造模式物品栏

**右键手机没有任何反应**，这是刻意的——与其现在写一个半截的开机逻辑，
不如让它老实地什么都不做，移植时一眼看得出这里还没接。

## 怎么往下做

看 [`docs/PORTING.md`](docs/PORTING.md)。那份清单是对 1.21.1 那支
**215 个文件、24,098 行**逐个扫出来的，不是凭印象列的：哪 111 个可以近乎照搬、
剩下 104 个各卡在哪一条 API 上、按什么顺序推进，都写在里面。

## 环境

| | |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.23 编译，运行时接受 `[47.4.0,)` |
| Java | **17**（不是 21） |
| Gradle | **8.8**（不是 9.x） |

后两条都不能跟着 1.21.1 那支走：

- **Java**：1.20.1 跑在 17 上，用 21 编出来的 class 文件版本低版本客户端加载不了，
  而且报的错不会提到 Java 版本
- **Gradle**：ForgeGradle 6 不支持 Gradle 9，配置阶段就会炸。两支各用各的 wrapper，
  这是刻意的，别为了"统一"把 `gradle-wrapper.properties` 改上去

## 构建

```bash
./gradlew build          # jar 出在 build/libs/
./gradlew runClient      # 开发环境启客户端
```

## 发版

tag 写成 `forge-v<版本>`（例：`forge-v0.1.0`）。`release.yml` 按这个前缀挑工程，
并校验 tag 里的版本号与本目录 `gradle.properties` 的 `mod_version` 一致。

**在移植到"能用"之前不要发 release** —— 挂上去的包玩家会真的下载。
