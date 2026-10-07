package com.november.mcphone.core.script.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 两个崩溃窗口、受益人固定、动态配额、退回交接格与未知记录保留。 */
public final class ItemEscrowLedgerTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {Path file=Files.createTempDirectory("mcphone-item-escrow").resolve("contents.json");AtomicLong now=new AtomicLong(1);AtomicReference<QuotaConfig> q=new AtomicReference<>(QuotaConfig.DEFAULT);UUID owner=UUID.randomUUID(),buyer=UUID.randomUUID();var ledger=new ItemEscrowLedger(file,now::get,q::get,p->0);
        var capture=ledger.capture(owner,buyer,"t:market","minecraft:overworld","{\"id\":\"minecraft:stone\"}");check(capture.recipient().equals(buyer),"创建时固定受益人");ledger=new ItemEscrowLedger(file,now::get,q::get,p->0);check(ledger.entry(capture.id()).state()==ItemEscrowLedger.State.UNKNOWN_CAPTURE,"重启不自动重扣");ledger.resolve(capture.id(),true);check(ledger.entry(capture.id()).state()==ItemEscrowLedger.State.HELD,"确认扣除后保留物品");
        try{ledger.delivery(capture.id(),UUID.randomUUID());throw new AssertionError("可替换收件人");}catch(IllegalStateException expected){checks++;}ledger.delivery(capture.id(),owner);check(ledger.used(owner)==1&&ledger.mailboxCount(owner)==0,"退回时腾出总物品交接格");ledger=new ItemEscrowLedger(file,now::get,q::get,p->0);check(ledger.entry(capture.id()).state()==ItemEscrowLedger.State.UNKNOWN_DELIVERY,"重启不自动重新交付");ledger.resolve(capture.id(),false);check(ledger.mailboxCount(owner)==1,"明确未交付才可退回待取状态");
        q.set(q.get().with("escrow.per_player",0));check(!ledger.canCapture(owner,"t:market"),"零配额禁止新增");check(ledger.entry(capture.id()).state()==ItemEscrowLedger.State.HELD,"降额不吞存量");ledger.delivery(capture.id(),owner);ledger.complete(capture.id());check(ledger.used(owner)==0,"关闭后仍能完成退回");
        var destroy=ledger.capture(owner,owner,"t:market","minecraft:overworld","{}",true);check(destroy.state()==ItemEscrowLedger.State.CAPTURING,"本人销毁不依赖托管配额开关");ledger.unknown(destroy.id());now.set(Long.MAX_VALUE-1);check(ledger.entries().size()==1,"结果不明永久保留待核对证据");ledger.resolve(destroy.id(),false);
        now.set(1);q.set(QuotaConfig.DEFAULT.with("escrow.per_app",1));var first=ledger.capture(owner,buyer,"t:market","minecraft:overworld","{}");ledger.held(first.id());check(!ledger.canCapture(owner,"t:market")&&ledger.canCapture(owner,"t:other"),"同 App 九格额度独立");
        var origin=ledger.capture(owner,buyer,"t:origin","minecraft:overworld","{}",false,"a".repeat(64),9007199254740993L,"full-author-key");ledger.held(origin.id());ledger.delivery(origin.id(),buyer);
        ledger=new ItemEscrowLedger(file,now::get,q::get,p->0);var restored=ledger.entry(origin.id());check(restored.revision().equals("a".repeat(64))&&restored.version()==9007199254740993L&&restored.author().equals("full-author-key"),"重启和状态转移保留原始包、大版本号和完整作者身份");
        ledger.resolve(origin.id(),false);check(ledger.entry(origin.id()).version()==origin.version(),"人工恢复不换成当前 App 版本");
        System.out.println("ItemEscrowLedgerTest: "+checks+" passed");
    }
}
