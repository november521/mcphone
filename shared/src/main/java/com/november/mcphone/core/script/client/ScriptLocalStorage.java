package com.november.mcphone.core.script.client;

import com.november.mcphone.core.client.ClientPaths;
import com.november.mcphone.core.script.server.store.LocalStore;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Uses the local login account, never a player UUID supplied by the connected server. */
final class ScriptLocalStorage {
    private ScriptLocalStorage() {}

    static BooleanSupplier accountGuard() {
        Minecraft mc = Minecraft.getInstance();
        UUID account = mc == null || mc.getUser() == null ? null : mc.getUser().getProfileId();
        return () -> {
            Minecraft current = Minecraft.getInstance();
            return account != null && current != null && current.getUser() != null
                    && account.equals(current.getUser().getProfileId());
        };
    }

    static Supplier<LocalStore> forApp(String appId) {
        Minecraft mc = Minecraft.getInstance();
        Path directory = ClientPaths.gameDirectoryOrNull();
        UUID account = mc == null || mc.getUser() == null ? null : mc.getUser().getProfileId();
        return LocalStore.clientProvider(directory, account, appId, () -> {
            Minecraft current = Minecraft.getInstance();
            return current == null || current.getUser() == null ? null : current.getUser().getProfileId();
        });
    }
}
