package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.*;
import com.november.mcphone.api.economy.Currency;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 外部钱包已入账、世界账未保存时，重启不能再次自动退款。 */
public final class SettlementJournalTest {
    private static int checks;
    private static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    private static void uncertain(Runnable operation,String why){try{operation.run();throw new AssertionError(why);}catch(IllegalStateException expected){checks++;}}
    private static ICurrencyProvider provider(EscrowLedger ledger,AtomicInteger credits,AtomicReference<TxnResult> result,AtomicBoolean crash){
        Currency currency=new Currency(ResourceLocation.tryParse("test:coin"),Component.literal("钱"),"",0,null);
        return (ICurrencyProvider)java.lang.reflect.Proxy.newProxyInstance(SettlementJournalTest.class.getClassLoader(),new Class[]{ICurrencyProvider.class},(p,m,a)->switch(m.getName()){
            case "currency"->currency;case "allowNegative"->false;case "maxBalance"->Long.MAX_VALUE;
            case "isAvailable"->true;case "unavailableReasonKey"->null;case "balance"->0L;
            case "refund","release"->{if(result.get()!=TxnResult.OK)yield result.get();credits.incrementAndGet();if(crash.get())throw new IllegalStateException("外部钱包入账后异常");ledger.settle((EscrowId)a[0]);yield TxnResult.OK;}
            default->throw new UnsupportedOperationException(m.getName());
        });
    }
    private static CurrencyRegistry registry(SettlementJournal journal,ICurrencyProvider provider){var gateway=new CurrencyGateway(Runnable::run,()->true);gateway.open();var r=new CurrencyRegistry(gateway,journal);r.register(provider,true);return r;}
    public static void main(String[]args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-settlement-test");AtomicLong now=new AtomicLong(1000);
        EscrowLedger ledger=new EscrowLedger(now::get,10);UUID owner=UUID.randomUUID(),buyer=UUID.randomUUID();EscrowId id=ledger.create(owner,buyer,"test:coin",9);
        Map<UUID,EscrowLedger.Entry> oldSave=ledger.snapshot();Path file=root.resolve("settlements.json");
        AtomicInteger credits=new AtomicInteger();AtomicReference<TxnResult> result=new AtomicReference<>(TxnResult.OK);AtomicBoolean crash=new AtomicBoolean(true);
        var journal=new SettlementJournal(file,ledger,now::get);var registry=registry(journal,provider(ledger,credits,result,crash));now.set(2000);
        check(EconomyRuntime.sweepEscrow(ledger,registry::get).suspect()==1,"超时退款异常被识别");check(credits.get()==1,"只进入外部钱包一次");
        EscrowLedger restartLedger=new EscrowLedger(now::get,10);restartLedger.restore(oldSave);
        var restarted=new SettlementJournal(file,restartLedger,now::get);check(restarted.entries().get(0).state()==SettlementJournal.State.UNKNOWN,"重启保留不明结算");
        crash.set(false);var afterRestart=registry(restarted,provider(restartLedger,credits,result,crash));
        EconomyRuntime.sweepEscrow(restartLedger,afterRestart::get);EconomyRuntime.sweepEscrow(restartLedger,afterRestart::get);check(credits.get()==1,"重启后的多次自动扫描不能重复入账");
        uncertain(()->afterRestart.get("test:coin").release(id,new TxnReason("manual","")),"不能换成放款绕开不明退款");check(credits.get()==1,"跨结算方法仍无副作用");
        check(restarted.settle("test:other","refund",id,()->TxnResult.OK)==TxnResult.NOT_AUTHORIZED,"别的币种不能使用结算号");
        restarted.resolve(id.value(),true);check(restartLedger.get(id).settled(),"确认已发生仅标记原账，不再给钱");
        check(afterRestart.get("test:coin").refund(id,new TxnReason("manual",""))==TxnResult.ALREADY_SETTLED,"确认后的重复退款明确已结清");check(credits.get()==1,"确认不再次调用钱包");
        var thirdLedger=new EscrowLedger(now::get,10);thirdLedger.restore(oldSave);new SettlementJournal(file,thirdLedger,now::get);check(thirdLedger.get(id).settled(),"确认记录重启后补标原账，不能复活退款");

        Path successFile=root.resolve("success.json");EscrowLedger successLedger=new EscrowLedger(now::get,10);EscrowId success=successLedger.create(owner,buyer,"test:coin",4);var beforeSuccess=successLedger.snapshot();
        var successJournal=new SettlementJournal(successFile,successLedger,now::get);var successRegistry=registry(successJournal,provider(successLedger,credits,result,crash));
        check(successRegistry.get("test:coin").refund(success,new TxnReason("ok",""))==TxnResult.OK,"正常退款成功");int after=credits.get();
        var rollback=new EscrowLedger(now::get,10);rollback.restore(beforeSuccess);var rollbackJournal=new SettlementJournal(successFile,rollback,now::get);
        check(rollbackJournal.entries().get(0).state()==SettlementJournal.State.UNKNOWN,"provider 成功但世界账回滚须核对");
        uncertain(()->rollbackJournal.settle("test:coin","refund",success,()->{credits.incrementAndGet();return TxnResult.OK;}),"完整快照回滚不能重复退款");check(credits.get()==after,"回滚后的入账次数保持");
        rollbackJournal.resolve(success.value(),false);check(rollbackJournal.settle("test:coin","refund",success,()->TxnResult.FAILED)==TxnResult.FAILED,"确认未发生后允许确定拒绝");
        check(rollbackJournal.entries().isEmpty(),"确定拒绝不留下不明标记");check(rollbackJournal.settle("test:coin","refund",success,()->TxnResult.OK)==TxnResult.OK,"下一次可以正常结算");

        Path startedFile=root.resolve("started.json");var fresh=new SettlementJournal(startedFile,rollback,now::get);EscrowId pending=rollback.create(owner,buyer,"test:coin",2);
        uncertain(()->fresh.settle("test:coin","refund",pending,()->{var midCrash=new SettlementJournal(startedFile,rollback,now::get);check(midCrash.entries().get(0).state()==SettlementJournal.State.UNKNOWN,"进入钱包前已保存 STARTED，强杀按不明恢复");throw new IllegalStateException("强杀窗口");}),"进入后异常须停止");
        Path corrupt=root.resolve("bad.json");Files.writeString(corrupt,"{broken}");var broken=new SettlementJournal(corrupt,rollback,now::get);check(!broken.problem().isEmpty(),"坏日志明确锁住");
        check(broken.settle("test:coin","refund",pending,()->{throw new AssertionError("坏日志仍碰钱包");})==TxnResult.UNAVAILABLE,"坏文件不能绕过落盘闸");check(Files.readString(corrupt).equals("{broken}"),"坏账不被空账覆盖");
        System.out.println("SettlementJournalTest: "+checks+" passed");
    }
}
