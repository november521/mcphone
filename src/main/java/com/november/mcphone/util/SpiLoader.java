package com.november.mcphone.util;

import com.november.mcphone.MCphone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 兜住异常的 SPI 扫描 —— 一个写坏的附属，不该让所有人的手机变空。
 *
 * ================================================================
 * 原来那种写法为什么是个坑
 * ================================================================
 *
 * 到处都写成这样：
 *
 *   for (IPhoneApp app : ServiceLoader.load(IPhoneApp.class)) { ... }
 *
 * 看着人畜无害，实际上 ServiceLoader 的迭代器在这几种情况下会抛
 * {@link java.util.ServiceConfigurationError}：
 *
 *   - services 文件里写的类名不存在（附属改了包名忘了同步）
 *   - 那个类没有公开的无参构造
 *   - 构造函数自己抛了异常
 *   - 类型对不上（写错了服务文件）
 *
 * 而它是从增强 for 里抛出来的——【整个循环当场中断】。后果是一条链子：
 *
 *   1. 某个附属的 App 构造失败
 *   2. 目录加载中断，内建的十来个 App 一个都没登记上
 *   3. 调用方的 loaded 标志早在循环之前就置真了，永远不会重试
 *   4. 而扫描是从渲染路径触发的，异常直接冲进渲染
 *
 * 玩家看到的是"装了某个模组之后手机里什么都没有了"，报错还指向别人的模组。
 *
 * 这与"谁都可以写 App"是直接冲突的：开放的前提是【一个人写坏了不能连累
 * 所有人】。CompatModules 那边早就写明白了同一个道理——一个写坏的兼容模块
 * 能让所有玩家的游戏起不来，而它本来只是锦上添花，代价完全不成比例。
 *
 * ================================================================
 * 为什么用 stream() 而不是直接 iterator()
 * ================================================================
 *
 * {@code ServiceLoader.stream()} 给的是 Provider 句柄，实例化推迟到
 * {@code Provider.get()}。这样"取下一个"和"把它造出来"就成了两步，可以
 * 分别兜——构造函数抛异常时我们还知道是哪个类干的，能把类名写进日志。
 *
 * 直接用 iterator() 的话两步是合一的，一抛异常就只剩一句"扫描失败"，
 * 服主根本无从知道该去卸载哪个模组。
 */
public final class SpiLoader {

    private SpiLoader() {}

    /**
     * 一次扫描最多容忍多少个坏条目。
     *
     * 有这个上限不是因为预计会坏这么多，而是因为下面那个循环在异常分支里
     * 走的是 continue：万一某个 JDK 实现在出错后不推进迭代器，没有上限就是
     * 一个死循环，而它发生在客户端启动路径上——游戏会卡死在黑屏，比崩溃还
     * 难查。
     */
    private static final int MAX_FAILURES = 32;

    /**
     * 扫描一类服务，坏的跳过、好的照常返回。
     *
     * @param service 服务接口，如 {@code IPhoneApp.class}
     * @param what    出错时写进日志的人话名字，如 "App"
     * @return 成功造出来的实例，顺序与 ServiceLoader 一致
     */
    public static <T> List<T> loadSafely(Class<T> service, String what) {
        List<T> out = new ArrayList<>();

        Iterator<ServiceLoader.Provider<T>> it;
        try {
            it = ServiceLoader.load(service).stream().iterator();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] {} 扫描无法开始，本次一个都不加载", what, t);
            return out;
        }

        int failures = 0;
        while (failures < MAX_FAILURES) {
            ServiceLoader.Provider<T> provider;

            // 第一步：取下一个候选。类名写错、类不存在会死在这儿，
            // 此时我们连是哪个类都不知道，只能报个位置
            try {
                if (!it.hasNext()) break;
                provider = it.next();
            } catch (Throwable t) {
                failures++;
                MCphone.LOGGER.error(
                        "[MCphone] 扫描 {} 时遇到一个坏条目（第 {} 个），已跳过。"
                                + "多半是某个模组的 META-INF/services 里写了不存在的类名",
                        what, failures, t);
                continue;
            }

            // 第二步：真把它造出来。构造函数抛异常死在这儿，
            // 这时候类名是知道的，写进日志好让服主知道该找谁
            try {
                T instance = provider.get();
                if (instance != null) out.add(instance);
            } catch (Throwable t) {
                failures++;
                MCphone.LOGGER.error("[MCphone] {} {} 构造失败，已跳过",
                        what, provider.type().getName(), t);
            }
        }

        if (failures >= MAX_FAILURES) {
            MCphone.LOGGER.error("[MCphone] {} 扫描中坏条目超过 {} 个，已停止扫描",
                    what, MAX_FAILURES);
        }
        return out;
    }
}
