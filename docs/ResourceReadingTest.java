package com.november.mcphone.api.sdk.resources;
import java.math.BigInteger;
public final class ResourceReadingTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){boolean refused=false;try{run.run();}catch(IllegalArgumentException expected){refused=true;}check(refused,why);}
    public static void main(String[]args){
        BigInteger total=BigInteger.ZERO;for(int i=0;i<20;i++)total=total.add(BigInteger.valueOf(Integer.MAX_VALUE));
        var reading=new ResourceReading(total,total,true,true);check(reading.stored().equals(new BigInteger("42949672940")),"20 个满储能累加不溢出");
        check(new ResourceReading(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN),BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN),false,false).stored().signum()>0,"Long 以上提供者读数精确");
        reject(()->new ResourceReading(BigInteger.valueOf(-1),BigInteger.TEN,false,false),"负读数拒绝");
        reject(()->new ResourceReading(BigInteger.TEN,BigInteger.ONE,false,false),"储量超过容量拒绝");
        reject(()->new ResourceReading(BigInteger.ZERO,BigInteger.TEN.pow(128),false,false),"恶意超大提供者读数拒绝");
        check(java.util.Arrays.stream(IResources.class.getDeclaredMethods()).allMatch(java.lang.reflect.Method::isDefault),"扩展只读接口保留已编译附属兼容性");
        check(java.util.Arrays.stream(IResources.class.getDeclaredMethods()).noneMatch(m->m.getName().equals("move")||m.getName().equals("mint")),"首版没有凭空造资源或绕过管线搬运接口");
        check(ResourcesApi.VERSION>=2,"客户端可通过 SDK 版本声明读取能力");
        System.out.println("ResourceReadingTest: "+checks+" passed");
    }
}
