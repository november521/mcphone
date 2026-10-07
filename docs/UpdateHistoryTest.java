package com.november.mcphone.core.script.pkg;

import java.nio.file.*;
public final class UpdateHistoryTest {
    public static void main(String[] args) throws Exception {
        Path file=Files.createTempDirectory("mcphone-version-test").resolve("history.json");
        UpdateHistory first=new UpdateHistory(file);
        if(!first.record("example:app|key",2,"a".repeat(64))) throw new AssertionError("首次安装");
        UpdateHistory back=new UpdateHistory(file);
        if(back.highest("example:app|key",1)!=2 || back.highest("example:app|key",3)!=3) throw new AssertionError("最高版本");
        if(back.record("example:app|key",1,"b".repeat(64))) throw new AssertionError("降级记录");
        if(back.record("example:app|key",2,"b".repeat(64))) throw new AssertionError("同版本不同摘要");
        if(!new UpdateHistory(file).conflicted("example:app|key")) throw new AssertionError("冲突未落盘");
        UpdateHistory stale=new UpdateHistory(file),parallel=new UpdateHistory(file);
        if(!stale.record("another:app|key",1,"c".repeat(64))||!parallel.record("third:app|key",1,"d".repeat(64)))throw new AssertionError("独立更新");
        UpdateHistory merged=new UpdateHistory(file);if(merged.highest("another:app|key",0)!=1||merged.highest("third:app|key",0)!=1)throw new AssertionError("并发实例不能覆盖别的 App");
        if(parallel.record("another:app|key",1,"e".repeat(64))||stale.record("another:app|key",1,"c".repeat(64)))throw new AssertionError("陈旧实例仍须看到冲突");
        System.out.println("版本历史 8 条断言，全部通过");
    }
}
