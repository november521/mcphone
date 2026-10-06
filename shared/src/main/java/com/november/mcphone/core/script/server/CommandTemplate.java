package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** 命令模板是服主审过的受控文本；没有自由字符串槽，也没有缓存解析结果。 */
public record CommandTemplate(String id, List<String> commands, Map<String, Argument> arguments,
                              Set<String> allowedPaths, boolean affectOthers, boolean reviewed, String selfFormat) {
    private static final Pattern SLOT = Pattern.compile("\\$\\{([a-z][a-z0-9_]{0,31})}");
    private static final Set<String> FORBIDDEN = Set.of("execute","function","op","deop","ban","ban-ip","pardon","pardon-ip","whitelist","mcphone");
    public record Argument(String type, int min, int max, List<String> values, String tag) {
        public Argument { values = List.copyOf(values); }
    }
    @FunctionalInterface public interface ResourceCheck { boolean tagged(String id, String tag); }
    public CommandTemplate {
        commands = List.copyOf(commands); arguments = Map.copyOf(arguments); allowedPaths = Set.copyOf(allowedPaths);
    }
    public static CommandTemplate parse(JsonObject o, Set<String> permissionPlugins) {
        String id = o.get("id").getAsString();
        if (!id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("命令模板 id 无效");
        boolean others = bool(o,"affect_others"), reviewed = bool(o,"reviewed");
        String format = o.has("self_format") ? o.get("self_format").getAsString() : "selector";
        if (!Set.of("selector","uuid").contains(format)) throw new IllegalArgumentException("self_format 无效");
        Map<String,Argument> args = new LinkedHashMap<>();
        JsonObject declared = o.has("arguments") ? o.getAsJsonObject("arguments") : new JsonObject();
        if (declared.size() > 16) throw new IllegalArgumentException("模板参数超额");
        for (var entry : declared.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("模板参数名无效");
            JsonObject a = entry.getValue().getAsJsonObject(); String type = a.get("type").getAsString();
            int min = 0, max = 0; List<String> values = new ArrayList<>(); String tag = "";
            switch (type) {
                case "self" -> { if (a.size()!=1) throw new IllegalArgumentException("self 不收客户端规则"); }
                case "int" -> { min = a.get("min").getAsBigDecimal().intValueExact(); max = a.get("max").getAsBigDecimal().intValueExact();
                    if (min > max || a.size()!=3) throw new IllegalArgumentException("int 要明确上下界"); }
                case "enum" -> { for (JsonElement value : a.getAsJsonArray("values")) { String v = value.getAsString(); token(v); if(values.contains(v)) throw new IllegalArgumentException("enum 重复"); values.add(v); }
                    if (values.isEmpty() || values.size()>16 || a.size()!=2) throw new IllegalArgumentException("enum 要 1–16 项"); }
                case "resource" -> { tag = a.get("tag").getAsString(); resource(tag); if(a.size()!=2) throw new IllegalArgumentException("resource 只收标签"); }
                default -> throw new IllegalArgumentException("不支持自由字符串参数");
            }
            args.put(entry.getKey(),new Argument(type,min,max,values,tag));
        }
        var lines = new ArrayList<String>(); var used = new HashSet<String>();
        for (JsonElement line : o.getAsJsonArray("commands")) {
            String text = line.getAsString();
            if (text.isEmpty() || text.length()>512 || !text.equals(text.trim()) || text.contains("  ")) throw new IllegalArgumentException("命令文本无效");
            Matcher matcher = SLOT.matcher(text); StringBuffer check = new StringBuffer();
            while (matcher.find()) { if(!args.containsKey(matcher.group(1))) throw new IllegalArgumentException("未声明参数槽"); used.add(matcher.group(1)); matcher.appendReplacement(check,"placeholder"); }
            matcher.appendTail(check);
            if (!check.toString().matches("[a-zA-Z0-9_:.+/@ -]+")) throw new IllegalArgumentException("命令文本含引号、NBT 或控制字符");
            String[] words = text.split(" "); String root = words[0];
            if (!root.matches("[a-z][a-z0-9_.-]*(:[a-z][a-z0-9_.-]*)?")) throw new IllegalArgumentException("命令根不能是变量");
            String bare = root.substring(root.lastIndexOf(':')+1);
            if (FORBIDDEN.contains(bare)) throw new IllegalArgumentException("永久禁止的命令族：" + bare);
            if (permissionPlugins.contains(bare)) {
                if (!format.equals("uuid") || args.values().stream().anyMatch(a -> !a.type().equals("self")))
                    throw new IllegalArgumentException("权限插件只允许 self UUID 槽，权限节点必须写死");
                if (!others && (!(bare.equals("lp") || bare.equals("luckperms")) || words.length<3
                        || !words[1].equals("user") || !self(words[2],args)))
                    throw new IllegalArgumentException("权限插件的目标必须是已验证的 self UUID 位置");
            }
            // 原版的目标参数位置逐族列出；未验证的第三方族一律要求红色的影响他人审批。
            int target = switch (bare) { case "give","attribute" -> 1; case "effect","experience","xp" -> 2;
                case "scoreboard" -> words.length>1 && words[1].equals("players") ? 3 : -1; default -> -1; };
            if (!others && !permissionPlugins.contains(bare)) {
                if (target<0 || words.length<=target || !self(words[target],args) || !format.equals("selector"))
                    throw new IllegalArgumentException("模板目标不是当前玩家；需要 affect_others 审批");
            }
            for (String word : words) if (word.contains("@") && !(word.equals("@s") || others && Set.of("@a","@e","@p","@r").contains(word)))
                throw new IllegalArgumentException("复杂或未审批的选择器");
            lines.add(text);
        }
        if (lines.isEmpty() || lines.size()>8 || !used.equals(args.keySet())) throw new IllegalArgumentException("模板要 1–8 条命令，参数必须全部被使用");
        Set<String> paths = new LinkedHashSet<>();
        for (JsonElement value : o.getAsJsonArray("allowed_paths")) {
            String path = value.getAsString(); if (!path.matches("/[a-zA-Z0-9_:./-]{1,256}") || !paths.add(path)) throw new IllegalArgumentException("解析节点路径无效");
        }
        if(paths.isEmpty() || paths.size()>32) throw new IllegalArgumentException("必须明确批准完整解析节点路径");
        return new CommandTemplate(id,lines,args,paths,others,reviewed,format);
    }
    private static boolean bool(JsonObject o,String key) {
        if (!o.has(key)) return false;
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException("模板开关要布尔值");
        return o.get(key).getAsBoolean();
    }
    private static boolean self(String word,Map<String,Argument> args) {
        if(word.equals("@s")) return true; Matcher m=SLOT.matcher(word);
        return m.matches() && args.get(m.group(1)).type().equals("self");
    }
    private static void token(String value) {
        if (!value.matches("[a-zA-Z0-9_.:/+-]{1,128}")) throw new IllegalArgumentException("参数含空格、选择器或转义字符");
    }
    private static void resource(String value) { if(!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.length()>128) throw new IllegalArgumentException("资源标识无效"); }
    public List<String> render(Map<String,Object> raw, UUID caller, ResourceCheck resources) {
        Set<String> required = new HashSet<>(); arguments.forEach((k,a)->{if(!a.type().equals("self")) required.add(k);});
        if(!raw.keySet().equals(required)) throw new IllegalArgumentException("模板参数缺失、额外字段或伪造 self");
        Map<String,String> values=new HashMap<>();
        arguments.forEach((key,a)-> {
            Object v=raw.get(key); String text;
            switch(a.type()) {
                case "self" -> text=selfFormat.equals("uuid")?caller.toString():"@s";
                case "int" -> { if(!(v instanceof Integer n) || n<a.min() || n>a.max()) throw new IllegalArgumentException("整数参数超界"); text=Integer.toString(n); }
                case "enum" -> { if(!(v instanceof String s) || !a.values().contains(s)) throw new IllegalArgumentException("参数不在枚举中"); token(s); text=s; }
                case "resource" -> { if(!(v instanceof String s)) throw new IllegalArgumentException("资源参数要标识"); resource(s);
                    if(!resources.tagged(s,a.tag())) throw new IllegalArgumentException("资源不在服主允许标签中"); text=s; }
                default -> throw new IllegalArgumentException("未支持参数");
            }
            values.put(key,text);
        });
        return commands.stream().map(line -> { Matcher m=SLOT.matcher(line); StringBuffer b=new StringBuffer();
            while(m.find()) m.appendReplacement(b,Matcher.quoteReplacement(values.get(m.group(1)))); m.appendTail(b); return b.toString(); }).toList();
    }
    public static boolean synchronousVanilla(String line) {
        String root=line.split(" ",2)[0]; return Set.of("give","effect","attribute","scoreboard","experience","xp").contains(root)
                || root.startsWith("minecraft:") && Set.of("give","effect","attribute","scoreboard","experience","xp").contains(root.substring(10));
    }
}
