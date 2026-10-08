import com.november.mcphone.core.client.RememberedPlayerSkins;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 原版皮肤供应器异步就绪、好友离线、重新连接与容量淘汰的回归检查。 */
public class RememberedPlayerSkinsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        UUID friend = new UUID(1, 1), unknown = new UUID(1, 2);
        var memory = new RememberedPlayerSkins<String>(512);
        Object connection = new Object();
        memory.bindConnection(connection);
        check(memory.resolve(unknown, "default").equals("default"), "从未见过的离线玩家使用默认皮肤");
        AtomicReference<String> texture = new AtomicReference<>("default");
        memory.remember(friend, texture::get);
        check(memory.resolve(friend, "default").equals("default"), "皮肤未下载完时允许占位");
        // 好友离开 Tab 列表，不再 remember；已保留的原版供应器仍能完成下载。
        texture.set("skin-a");
        check(memory.resolve(friend, "default").equals("skin-a"), "好友下线后仍可完成原版皮肤加载");
        texture.set("default");
        check(memory.resolve(friend, "default").equals("skin-a"), "默认占位不能覆盖已知真皮肤");
        texture.set(null);
        check(memory.resolve(friend, "default").equals("skin-a"), "暂时没有贴图也不丢失已知皮肤");
        texture.set("skin-b");
        check(memory.resolve(friend, "default").equals("skin-b"), "新皮肤可替换旧皮肤");
        check(!memory.bindConnection(connection), "同一连接不释放当前资料");
        memory.bindConnection(null);
        texture.set("old-connection-result");
        check(memory.resolve(friend, "default").equals("skin-b"), "自己断线后不再读取旧 PlayerInfo");
        memory.bindConnection(new Object());
        check(memory.resolve(friend, "default").equals("skin-b"), "当前客户端重新进服仍保留已知皮肤引用");
        AtomicReference<String> fresh = new AtomicReference<>("default");
        memory.remember(friend, fresh::get);
        check(memory.resolve(friend, "default").equals("skin-b"), "重新上线加载中不闪回默认头像");
        fresh.set("skin-c");
        check(memory.resolve(friend, "default").equals("skin-c"), "新连接中的真皮肤更新正常");
        memory.remember(unknown, () -> "default");
        memory.bindConnection(new Object());
        check(memory.resolve(unknown, "another-default").equals("another-default"), "未加载的占位资料不带进下个连接");

        var bounded = new RememberedPlayerSkins<String>(2);
        bounded.bindConnection(new Object());
        UUID a = new UUID(2, 1), b = new UUID(2, 2), c = new UUID(2, 3);
        bounded.remember(a, () -> "a"); bounded.resolve(a, "default");
        bounded.remember(b, () -> "b"); bounded.resolve(b, "default");
        bounded.resolve(a, "default");
        bounded.remember(c, () -> "c"); bounded.resolve(c, "default");
        check(bounded.resolve(b, "default").equals("default"), "超过容量淘汰最久未使用的资料");
        check(bounded.resolve(a, "default").equals("a"), "常用好友皮肤保留");
        check(bounded.resolve(c, "default").equals("c"), "新资料正常保留");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
