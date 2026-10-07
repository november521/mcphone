package com.november.mcphone.core.script.client;

import com.google.gson.JsonObject;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.feature.store.client.NativeAdminPage;
import com.november.mcphone.feature.store.client.ServerManagementPage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import org.lwjgl.glfw.GLFW;

/** 无窗口调用真实页面的点击/键盘入口，防止内容行无法聚焦和 E 键抢走输入。 */
public final class NativeAdminInputTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static Field field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static void set(Object target,String name,Object value)throws Exception{field(target,name).set(target,value);}
    private static Object get(Object target,String name)throws Exception{return field(target,name).get(target);}
    private static void invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
    private static void geometry(Object page)throws Exception{set(page,"x",10);set(page,"y",20);set(page,"width",120);set(page,"height",220);}
    private static void type(IPhonePage page,String text){for(char c:text.toCharArray())check(page.charTyped(c,0),"聚焦后的字符由页面接收");}
    private static List<?> rows(Object page)throws Exception{return (List<?>)get(page,"rows");}
    private static String rowText(Object page,int index)throws Exception{Object row=rows(page).get(index);Method text=row.getClass().getDeclaredMethod("text");text.setAccessible(true);return (String)text.invoke(row);}
    private static void adminSession(){
        ClientHandshake.clear();ClientAdministration.clear();
        ClientHandshake.onPush(ClientHandshakeTest.begin(1,ClientHandshakeTest.EPOCH,0));
        ClientHandshake.onPush(ClientHandshakeTest.end(2,ClientHandshakeTest.EPOCH));
        JsonObject policy=new JsonObject();policy.addProperty("serverId",ClientHandshakeTest.SERVER.toString());policy.addProperty("epoch",Long.toString(ClientHandshakeTest.EPOCH));policy.addProperty("admin",true);policy.addProperty("appLimit",32);
        ClientAdministration.push(new ScriptPush(ScriptProtocol.HOST_APP_ID,"mcphone:network.policy",policy.toString().getBytes(StandardCharsets.UTF_8),3));
        check(ClientAdministration.admin(),"点击测试使用当前连接的管理角色");
    }
    private static void uuidField()throws Exception{
        NativeAdminPage page=new NativeAdminPage();geometry(page);
        UnaryOperator<String> fit=s->s.substring(0,Math.min(8,s.length()));
        invoke(page,"field",new Class<?>[]{int.class,String.class,UnaryOperator.class},0,"玩家 UUID",fit);
        check(!page.capturesKeyboard(),"未点字段不抢键盘");
        check(!page.mouseClicked(140,38,0),"字段外点击不聚焦");
        check(page.mouseClicked(14,38,0),"空内容行可点击");
        check(page.capturesKeyboard(),"内容行聚焦后先于背包键收按键");
        check(get(page,"message").toString().contains("正在编辑：玩家 UUID"),"聚焦有明确提示");
        String uuid="ebc4dc98-0bdd-415f-b57f-bd68c9ef0cb5";type(page,uuid);
        TextInputBuffer input=((TextInputBuffer[])get(page,"fields"))[0];
        check(input.text().equals(uuid),"UUID 包含 e 时仍完整写入");
        check(page.keyPressed(GLFW.GLFW_KEY_HOME,0,0),"Home 可移动光标");
        check(input.cursor()==0,"Home 到开头");
        check(page.keyPressed(GLFW.GLFW_KEY_RIGHT,0,GLFW.GLFW_MOD_SHIFT),"Shift 方向键可选中");
        check(input.selected().equals("e"),"按码点选择首字符");
        check(page.keyPressed(GLFW.GLFW_KEY_END,0,0)&&input.cursor()==uuid.length(),"End 到末尾");
        rows(page).clear();invoke(page,"field",new Class<?>[]{int.class,String.class,UnaryOperator.class},0,"玩家 UUID",fit);
        check(rowText(page,0).contains("输入中"),"标签展示焦点状态");
        check(rows(page).size()>3,"窄手机中的 UUID 分行");
        int last=rows(page).size()-1;
        check(page.mouseClicked(14,22+13*last+3,0),"UUID 最后一行也可聚焦");
        check(input.selected().equals(uuid),"点击内容行全选原内容，方便粘贴替换");
        check(page.keyPressed(GLFW.GLFW_KEY_ENTER,0,0)&&!page.capturesKeyboard(),"Enter 完成后释放键盘");
        check(!page.charTyped('x',0),"完成后不误写字段");
        page.mouseClicked(14,38,0);page.onClose();check(!page.capturesKeyboard(),"关页释放焦点");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void reviewFields()throws Exception{
        ServerManagementPage page=new ServerManagementPage();geometry(page);
        Class<? extends Enum> view=(Class<? extends Enum>)field(page,"view").getType();set(page,"view",Enum.valueOf(view,"CONFIRM"));
        invoke(page,"build",new Class<?>[0]);check(!page.capturesKeyboard(),"审核确认页初始不抢键盘");
        check(page.mouseClicked(14,68,0)&&page.capturesKeyboard(),"点击理由内容进入输入");
        type(page,"审核test");TextInputBuffer reason=(TextInputBuffer)get(page,"reason");
        check(reason.text().equals("审核test"),"点击理由全选并替换默认文本");
        check(page.capturesKeyboard(),"理由中的 e 不让背包键处理");
        invoke(page,"build",new Class<?>[0]);check(rowText(page,1).contains("输入中"),"审核理由有可见焦点");
        check(page.keyPressed(GLFW.GLFW_KEY_TAB,0,0),"Tab 转到指纹");type(page,"1052-3283-6829-06a6");
        check(((TextInputBuffer)get(page,"phrase")).text().equals("1052-3283-6829-06a6"),"指纹独立保存");
        check(reason.text().equals("审核test"),"输入指纹不覆盖理由");
        check(page.keyPressed(GLFW.GLFW_KEY_ENTER,0,0)&&!page.capturesKeyboard(),"审核 Enter 完成后释放输入");
        check(get(page,"message").toString().contains("输入完成"),"完成编辑明确提示");
        page.mouseClicked(14,55,0);check(page.capturesKeyboard(),"理由标签也能聚焦");page.onClose();check(!page.capturesKeyboard(),"关闭审核页清理焦点");
    }
    public static void main(String[] args)throws Exception{
        try{adminSession();uuidField();reviewFields();System.out.println("NativeAdminInputTest: "+checks+" passed");}
        finally{ClientAdministration.clear();ClientHandshake.clear();}
    }
}
