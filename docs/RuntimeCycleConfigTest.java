package com.november.mcphone.core.script.server;
import com.november.mcphone.api.sdk.cycle.*;
import java.time.*;
public final class RuntimeCycleConfigTest {
    private static int checks;
    private static void check(boolean v){checks++;if(!v)throw new AssertionError("周期配置 "+checks);}
    private static void reject(String json){try{ScriptRuntimeConfig.parse(json);throw new AssertionError("错误时区被接受");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[]args){
        String base="{\"cost_strategy\":\"unsupported\"";
        reject(base+"}");reject(base+",\"cycle\":{\"zone\":\"\"}}");reject(base+",\"cycle\":{\"zone\":true}}");reject(base+",\"cycle\":{\"zone\":\"invalid-zone\"}}");
        var config=ScriptRuntimeConfig.parse(base+",\"cycle\":{\"zone\":\"Asia/Shanghai\"}}");check(config.dailyAt().equals(LocalTime.of(4,0)));
        check(!config.serverScripts());reject(base+",\"cycle\":{\"zone\":\"UTC\"},\"server_scripts\":\"true\"}");reject(base+",\"cycle\":{\"zone\":\"UTC\"},\"cross_server_trust\":true}");
        check(ScriptRuntimeConfig.parse(base+",\"cycle\":{\"zone\":\"UTC\"},\"server_scripts\":true}").serverScripts());
        Instant before=Instant.parse("2026-10-05T19:59:59Z"),after=before.plusSeconds(1);
        check(!CycleLabels.label(CycleKind.DAILY,before,config.zone(),config.dailyAt()).equals(CycleLabels.label(CycleKind.DAILY,after,config.zone(),config.dailyAt())));
        var existing=ScriptRuntimeConfig.parse(base+",\"cycle\":{\"zone\":\"UTC\",\"daily_at\":\"00:00\"}}");check(existing.dailyAt().equals(LocalTime.MIDNIGHT));
        check(CycleLabels.nextBoundary(CycleKind.DAILY,before,config.zone(),config.dailyAt())==after.toEpochMilli());
        System.out.println("RuntimeCycleConfigTest: "+checks+" passed");
    }
}
