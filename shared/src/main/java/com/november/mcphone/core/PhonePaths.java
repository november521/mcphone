package com.november.mcphone.core;

import java.nio.file.Path;
import java.util.Objects;

/** Runtime file layout. Resolving a location has no filesystem side effects. */
public enum PhonePaths {
    // Relative to the game/server directory.
    APPS("mcphone/apps"),
    MUSIC("mcphone/music"),
    BOOKS("mcphone/books"),
    READER_PROGRESS("mcphone/data/reader/progress.json"),
    READER_SHELF("mcphone/data/reader/shelf.json"),
    WALLPAPERS("mcphone/wallpapers"),
    STICKERS("mcphone/stickers"),
    INSTALLED("mcphone/data/installed"),
    APP_DATA("mcphone/data/appdata"),
    FAVORITES("mcphone/data/chat/favorites"),
    CHANGELOG("mcphone/data/changelog"),
    AUTHORS("config/mcphone/authors.json"),
    AUTHOR_KEYS("config/mcphone/keys"),
    AUTHOR_BACKUP("mcphone/backups/author-keys"),
    SCREENSHOTS("screenshots"),

    // Relative to the current world, never to the client game directory.
    WORLD_INCOMING("mcphone/store/incoming"),
    WORLD_ECONOMY("mcphone/economy"),
    WORLD_GROUP_IMAGES("mcphone/group-images"),
    WORLD_CAPABILITIES("serverconfig/mcphone-capabilities.json"),
    WORLD_ECONOMY_CONFIG("serverconfig/mcphone-economy.json");

    private final String relative;

    PhonePaths(String relative) { this.relative = relative; }

    /** Relative layout shared by feature constants and documentation. */
    public String relative() { return relative; }

    /** Callers provide the actual game/server directory, or world root for WORLD_* entries. */
    public Path in(Path root) { return Objects.requireNonNull(root, "directory").resolve(relative); }
}
