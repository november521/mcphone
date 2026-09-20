package com.november.mcphone.core.script.server.economy;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * {@code /mcphone economy audit}（§22.10）：把每种货币的账对一遍，<b>不平的那一行要显眼</b>。
 *
 * <p>要 OP 3 级：余额是所有人的隐私，对账结果本身也会暴露服务器的经济规模。
 * <b>权限挂在 {@code economy} 上，不许挂在 {@code mcphone} 根上</b>：brigadier 合并同名节点时只留先注册那一个的
 * requirement —— 挂在根上，别的 {@code /mcphone} 子命令先注册就让普通玩家也能对账，后注册就让它们对普通玩家消失。
 */
public final class EconomyCommand {

    private EconomyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcphone")
                .then(Commands.literal("economy")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("audit").executes(ctx -> audit(ctx.getSource())))));
    }

    private static int audit(CommandSourceStack src) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) {
            src.sendFailure(Component.literal("[对账] 货币系统没在运行"));
            return 0;
        }
        EconomyData data = rt.data();
        if (data.wholeLock() != null) {
            src.sendFailure(Component.literal("[对账] ⚠⚠ 货币存档整份锁住了，没法对账：" + data.wholeLock()));
            return 0;
        }
        Set<String> ids = data.currencyIds();
        if (ids.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[对账] 还没有任何货币的账"), false);
            return 1;
        }
        Set<String> locked = data.lockedCurrencies();
        int unbalanced = 0;
        for (String id : ids) {
            if (locked.contains(id)) {
                src.sendFailure(Component.literal("[对账] ⚠⚠ " + id + " 的存档读坏了、锁住了，跳过（细节见服务器日志）"));
                unbalanced++;
                continue;
            }
            // 一律按 builtin 档对：余额取自世界存档。S15f 只为"存档里已出现过的货币"注册 builtin 档
            // （面额表属 S15d′），所以现在账上不可能出现别的档。等 S15d′ 按配置注册计分板/外部钱包档之后，
            // 这里要按 provider 的档区分 —— 计分板档的余额不在这份存档里，照这样对出来必然不平
            EconomyAudit.Result r = EconomyAudit.run(id, data);
            if (!r.balanced()) unbalanced++;
            Component line = Component.literal(r.describe())
                    .withStyle(r.balanced() ? ChatFormatting.GREEN : ChatFormatting.RED);
            src.sendSuccess(() -> line, false);
        }
        return unbalanced == 0 ? 1 : 0;
    }
}
