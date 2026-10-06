package com.november.mcphone.core.script.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class MailboxLedgerTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("收件箱检查 " + checks); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-mailbox"); Path path = dir.resolve("contents.json");
        AtomicLong now = new AtomicLong(1); UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        try {
            MailboxLedger box = new MailboxLedger(path, now::get);
            check(box.deposit(owner, Collections.nCopies(26, "{\"nbt\":\"保持原样😀\"}"), "reward"));
            check(!box.deposit(owner, List.of("a", "b"), "batch")); check(box.entries(owner).size() == 26);
            check(box.deposit(owner, List.of("last"), "batch")); check(box.entries(owner).size() == 27);
            String id = box.entries(owner).get(0).id();
            try { box.begin(stranger, id); throw new AssertionError("越权领取"); } catch (IllegalArgumentException denied) { checks++; }
            box.begin(owner, id);
            box = new MailboxLedger(path, now::get);
            check(box.entries(owner).get(0).state() == MailboxLedger.State.UNKNOWN);
            try { box.begin(owner, id); throw new AssertionError("不明结果被重发"); } catch (IllegalStateException denied) { checks++; }
            box.resolve(owner, id, false); box.begin(owner, id); box.complete(owner, id);
            check(box.entries(owner).size() == 26);
            box = new MailboxLedger(path, now::get); check(box.entries(owner).size() == 26);
            now.set(MailboxLedger.RETENTION_MS + 1); check(box.expire().size() == 26); check(box.entries(owner).isEmpty());
            now.set(MailboxLedger.RETENTION_MS+2);
            List<String> receiving=box.beginDeposit(owner,List.of("own-nbt","minted-nbt"),"transfer",true);
            check(receiving.size()==2&&box.entries(owner).stream().allMatch(e->e.state()==MailboxLedger.State.RECEIVING));
            try{box.begin(owner,receiving.get(0));throw new AssertionError("未扣除的物品可领取");}catch(IllegalStateException denied){checks++;}
            box=new MailboxLedger(path,now::get);check(box.entries(owner).stream().allMatch(e->e.state()==MailboxLedger.State.UNKNOWN&&e.incoming()));
            box.resolve(owner,receiving.get(0),true);check(box.entries(owner).get(0).state()==MailboxLedger.State.AVAILABLE);
            box.resolve(owner,receiving.get(1),false);check(box.entries(owner).size()==1);
            box.begin(owner,receiving.get(0));box.complete(owner,receiving.get(0));check(box.entries(owner).isEmpty());
            List<String> completed=box.beginDeposit(owner,List.of("nbt"),"transfer",true);box.received(owner,completed);
            box=new MailboxLedger(path,now::get);check(box.entries(owner).get(0).state()==MailboxLedger.State.AVAILABLE&&!box.entries(owner).get(0).incoming());
            Files.writeString(path, "bad");
            try { new MailboxLedger(path, now::get); throw new AssertionError("损坏文件被清零"); } catch (IllegalArgumentException denied) { checks++; }
            System.out.println("MailboxLedgerTest: " + checks + " checks passed");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(dir.resolve("contents.json.tmp")); Files.deleteIfExists(dir); }
    }
}
