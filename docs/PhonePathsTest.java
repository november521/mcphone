package com.november.mcphone.core;

import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.feature.chat.client.favorite.ChatFavorites;
import com.november.mcphone.feature.settings.client.changelog.ChangelogStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real files verify new roots, unchanged identities and no old-path compatibility. */
public final class PhonePathsTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static void run(Path root) throws Exception {
        Path instance = root.resolve("游戏实例 A");
        for (PhonePaths path : PhonePaths.values()) path.in(instance);
        check(!Files.exists(instance), "resolving paths has no filesystem side effects");
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String app = "example:paths", scope = "remote:example.com:25565";
        Path oldStore = instance.resolve("config/mcphone/appdata/client").resolve(account.toString()).resolve(hash(app) + ".json");
        Files.createDirectories(oldStore.getParent()); Files.writeString(oldStore, "{\"name\":\"old value\"}");
        Path oldMusic = instance.resolve("config/mcphone/music/旧歌.mp3");
        Files.createDirectories(oldMusic.getParent()); Files.writeString(oldMusic, "old music");
        Path store = LocalStore.clientPath(instance, account, app);
        check(store.equals(instance.resolve("mcphone/data/appdata/client").resolve(account.toString()).resolve(hash(app) + ".json")), "production store uses new root and original App hash");
        check(LocalStore.openClient(instance, account, app).getString("name", "missing").equals("missing"), "old store is not read automatically");
        LocalStore fresh = LocalStore.openClient(instance, account, app); fresh.setString("name", "new value");
        check(fresh.save() && LocalStore.openClient(instance, account, app).getString("name", "").equals("new value"), "actual save and read use new directory");
        check(Files.readString(oldStore).equals("{\"name\":\"old value\"}") && Files.readString(oldMusic).equals("old music"), "old files remain untouched");
        check(PhonePaths.MUSIC.in(instance).equals(instance.resolve("mcphone/music")) && !Files.exists(PhonePaths.MUSIC.in(instance)), "music does not import or fall back to old folder");
        check(!Files.exists(instance.resolve("mcphone/.migrations")), "no migration metadata created");
        check(ChatFavorites.path(instance, account, scope).equals(instance.resolve("mcphone/data/chat/favorites").resolve(account.toString()).resolve(hash(scope) + ".json")), "favorites keep account/server identities under new root");
        check(ChangelogStore.path(instance, account, scope).equals(instance.resolve("mcphone/data/changelog").resolve(account.toString()).resolve(hash(scope) + ".txt")), "changelog keeps account/scope identities");
        check(!LocalStore.clientPath(instance, UUID.randomUUID(), app).equals(store), "account isolation retained");
        check(!LocalStore.clientPath(instance, account, "another:app").equals(store), "App isolation retained");
        Path other = root.resolve("游戏实例 B");
        check(LocalStore.openClient(other, account, app).getString("name", "missing").equals("missing"), "different game instances remain isolated");
        check(!Files.exists(other), "read-only empty lookup does not create directories");
        check(PhonePaths.AUTHORS.in(instance).equals(instance.resolve("config/mcphone/authors.json")), "trust configuration stays in config");
        check(PhonePaths.AUTHOR_KEYS.in(instance).equals(instance.resolve("config/mcphone/keys")), "signing keys stay in config");
        check(PhonePaths.SCREENSHOTS.in(instance).equals(instance.resolve("screenshots")), "vanilla screenshots unchanged");
        check(PhonePaths.WORLD_ECONOMY.in(root.resolve("world")).equals(root.resolve("world/mcphone/economy")), "world state stays anchored to world root");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-paths-test-").toAbsolutePath().normalize();
        try { run(root); }
        finally {
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("Unexpected cleanup root");
            try (var files = Files.walk(root)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
        System.out.println("PhonePathsTest: " + checks + " checks passed");
    }
}
