package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 外部钱包真实效果计数验证越权零触达、重启可结算以及未知结果禁止重试。 */
public final class ScriptCurrencyEscrowsTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {
        Path dir=Files.createTempDirectory("mcphone-currency-ownership"),file=dir.resolve("escrows.json");
        AtomicLong now=new AtomicLong(100);UUID owner=UUID.randomUUID(),buyer=UUID.randomUUID();
        var scope=new ScriptCurrencyEscrows.Scope("test:market","full-author-key",owner);
        var id=new EscrowId(UUID.randomUUID());AtomicInteger holds=new AtomicInteger(),releases=new AtomicInteger();
        try {
            var ledger=new ScriptCurrencyEscrows(file,now::get);
            check(ledger.hold(scope,"test:coin",buyer,owner,10,()->{holds.incrementAndGet();return HoldResult.ok(id);}).result()==TxnResult.NOT_AUTHORIZED,"不能扣第三方的钱");
            check(holds.get()==0,"越权在触达钱包前拒绝");
            check(ledger.hold(scope,"test:coin",owner,buyer,10,()->{holds.incrementAndGet();return HoldResult.ok(id);}).id().equals(id),"创建并保存归属");
            ledger=new ScriptCurrencyEscrows(file,now::get);
            var effects=(java.util.function.Supplier<TxnResult>)()->{releases.incrementAndGet();return TxnResult.OK;};
            check(ledger.settle(new ScriptCurrencyEscrows.Scope("test:market","full-author-key",buyer),"test:coin",id,effects)==TxnResult.NOT_AUTHORIZED,"另一玩家拿到号也无权结算");
            check(ledger.settle(new ScriptCurrencyEscrows.Scope("test:other","full-author-key",owner),"test:coin",id,effects)==TxnResult.NOT_AUTHORIZED,"另一 App 无权结算");
            check(ledger.settle(new ScriptCurrencyEscrows.Scope("test:market","new-author-key",owner),"test:coin",id,effects)==TxnResult.NOT_AUTHORIZED,"同名新作者不能接管旧托管");
            check(ledger.settle(scope,"test:other",id,effects)==TxnResult.NOT_AUTHORIZED,"币种不能换");
            check(releases.get()==0,"四种越权均未进入 provider");
            check(ledger.settle(scope,"test:coin",new EscrowId(UUID.randomUUID()),effects)==TxnResult.UNKNOWN_ESCROW,"未知号无效果");
            check(ledger.settle(scope,"test:coin",id,()->TxnResult.UNAVAILABLE)==TxnResult.UNAVAILABLE,"确定拒绝允许后续再试");
            check(ledger.settle(scope,"test:coin",id,effects)==TxnResult.OK,"重启后原玩家和原作者可正常放款");
            check(ledger.settle(scope,"test:coin",id,effects)==TxnResult.ALREADY_SETTLED&&releases.get()==1,"结清号不会第二次进入钱包");
            var uncertainId=new EscrowId(UUID.randomUUID());ledger.hold(scope,"test:coin",owner,buyer,20,()->HoldResult.ok(uncertainId));
            try{ledger.settle(scope,"test:coin",uncertainId,()->{releases.incrementAndGet();throw new IllegalStateException("已入账后保存失败");});throw new AssertionError("未知结果被吞掉");}catch(IllegalStateException expected){checks++;}
            ledger=new ScriptCurrencyEscrows(file,now::get);
            try{ledger.settle(scope,"test:coin",uncertainId,effects);throw new AssertionError("重启后未知结果重试");}catch(IllegalStateException expected){checks++;}
            check(releases.get()==2,"未知结果未二次调用钱包");
            ledger.resolve(uncertainId.value(),true);check(ledger.settle(scope,"test:coin",uncertainId,effects)==TxnResult.ALREADY_SETTLED,"人工确认已结算不会再放款");
            var crashId=new EscrowId(UUID.randomUUID());ledger.hold(scope,"test:coin",owner,buyer,30,()->HoldResult.ok(crashId));
            Files.writeString(file,Files.readString(file).replace("\"state\":\"HELD\"","\"state\":\"SETTLING\""));
            ledger=new ScriptCurrencyEscrows(file,now::get);
            try{ledger.settle(scope,"test:coin",crashId,effects);throw new AssertionError("崩溃标记被重放");}catch(IllegalStateException expected){checks++;}
            ledger.resolve(crashId.value(),false);check(ledger.settle(scope,"test:coin",crashId,effects)==TxnResult.OK,"人工核对未执行后显式恢复");
            var heldId=new EscrowId(UUID.randomUUID());ledger.hold(scope,"test:coin",owner,buyer,1,()->HoldResult.ok(heldId));now.addAndGet(EscrowLedger.SETTLED_KEEP_MS+1);
            ledger.hold(scope,"test:coin",owner,buyer,1,()->HoldResult.ok(new EscrowId(UUID.randomUUID())));
            check(ledger.entries().stream().anyMatch(e->e.id().equals(heldId.value())),"保留期不会删除未结清资金");
            check(ledger.entries().stream().noneMatch(e->e.id().equals(id.value())),"只清理到期的已结清归属");
            var versioned=new ScriptCurrencyEscrows.Scope("test:market","full-author-key",owner,"a".repeat(64),9007199254740993L);var versionedId=new EscrowId(UUID.randomUUID());ledger.hold(versioned,"test:coin",owner,buyer,1,()->HoldResult.ok(versionedId));
            ledger=new ScriptCurrencyEscrows(file,now::get);check(ledger.entries().stream().anyMatch(e->e.id().equals(versionedId.value())&&e.scope().version()==9007199254740993L&&e.scope().revision().equals("a".repeat(64))),"原始版本与包摘要精确保存");
            check(ledger.settle(new ScriptCurrencyEscrows.Scope("test:market","full-author-key",owner,"b".repeat(64),9007199254740994L),"test:coin",versionedId,effects)==TxnResult.OK,"同作者获批新版可结算旧托管，审计仍保留创建版本");
            Files.writeString(file,"bad");try{new ScriptCurrencyEscrows(file,now::get);throw new AssertionError("损坏归属账被清零");}catch(RuntimeException expected){checks++;}
            System.out.println("ScriptCurrencyEscrowsTest: "+checks+" passed");
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(dir.resolve("escrows.json.tmp"));Files.delete(dir);}
    }
}
