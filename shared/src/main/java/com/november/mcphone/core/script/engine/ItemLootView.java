package com.november.mcphone.core.script.engine;
import java.util.*;
/** 真实掷取只在服务器主线程进行，返回宿主签发的引用；发放仍由意图落地处理。 */
public interface ItemLootView extends ItemView {
    List<Map<String,Object>> roll(String table);
}
