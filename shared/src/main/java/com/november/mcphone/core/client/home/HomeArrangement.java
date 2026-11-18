package com.november.mcphone.core.client.home;

import com.november.mcphone.core.client.HomeLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ordered launcher entries for one world. App IDs are shared with the installation registry. */
public final class HomeArrangement {
    public static final int MAX_NAME_LENGTH = 24;

    public sealed interface Entry permits App, Folder {
        String id();
    }

    public record App(String id) implements Entry {}

    public record Folder(String id, String name, List<String> members) implements Entry {
        public Folder {
            members = List.copyOf(members);
        }
    }

    /** A null members list represents an app. An empty list represents an empty folder. */
    public record StoredEntry(String id, String name, List<String> members) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> view = Collections.unmodifiableList(entries);

    public List<Entry> entries() {
        return view;
    }

    public Folder folder(String id) {
        int index = indexOf(id);
        return index >= 0 && entries.get(index) instanceof Folder folder ? folder : null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    public void restore(List<String> installed, List<StoredEntry> saved) {
        entries.clear();
        Set<String> remaining = new HashSet<>(installed);
        Set<String> folders = new HashSet<>();
        if (saved != null) {
            for (StoredEntry entry : saved) {
                if (entry == null || entry.id() == null) continue;
                if (entry.members() == null) {
                    if (remaining.remove(entry.id())) entries.add(new App(entry.id()));
                    continue;
                }

                String name = validName(entry.name());
                if (!isFolderId(entry.id()) || name == null || !folders.add(entry.id())) continue;
                List<String> members = entry.members().stream().filter(remaining::remove).toList();
                if (!members.isEmpty()) entries.add(new Folder(entry.id(), name, members));
            }
        }
        for (String id : installed) {
            if (remaining.remove(id)) entries.add(new App(id));
        }
    }

    public List<StoredEntry> snapshot() {
        return entries.stream().map(entry -> entry instanceof Folder folder
                ? new StoredEntry(folder.id(), folder.name(), folder.members())
                : new StoredEntry(entry.id(), null, null)).toList();
    }

    /** Keeps the flat installation order readable by clients that predate folders. */
    public List<String> appsInOrder() {
        List<String> apps = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof Folder folder) apps.addAll(folder.members());
            else apps.add(entry.id());
        }
        return apps;
    }

    public void reconcile(List<String> installed) {
        restore(installed, snapshot());
    }

    public boolean move(String id, int to) {
        return HomeLayout.reorder(entries, indexOf(id), to);
    }

    public boolean group(String source, String target, String defaultName) {
        int from = indexOf(source);
        int to = indexOf(target);
        if (from < 0 || to < 0 || from == to || !(entries.get(from) instanceof App)) return false;
        if (entries.get(to) instanceof Folder) return add(target, List.of(source));

        String name = validName(defaultName);
        if (name == null) return false;
        Folder folder = new Folder(UUID.randomUUID().toString(), name, List.of(target, source));
        entries.set(to, folder);
        entries.remove(from);
        return true;
    }

    public boolean add(String folderId, List<String> ids) {
        Folder folder = folder(folderId);
        if (folder == null) return false;

        List<Entry> selected = entries.stream()
                .filter(entry -> entry instanceof App && ids.contains(entry.id())).toList();
        if (selected.isEmpty()) return false;

        List<String> members = new ArrayList<>(folder.members());
        selected.forEach(app -> members.add(app.id()));
        entries.set(indexOf(folderId), new Folder(folderId, folder.name(), members));
        entries.removeAll(selected);
        return true;
    }

    public boolean rename(String folderId, String value) {
        Folder folder = folder(folderId);
        String name = validName(value);
        if (folder == null || name == null || name.equals(folder.name())) return false;
        entries.set(indexOf(folderId), new Folder(folderId, name, folder.members()));
        return true;
    }

    public boolean moveMember(String folderId, String app, int to) {
        Folder folder = folder(folderId);
        if (folder == null) return false;
        List<String> members = new ArrayList<>(folder.members());
        if (!HomeLayout.reorder(members, members.indexOf(app), to)) return false;
        entries.set(indexOf(folderId), new Folder(folderId, folder.name(), members));
        return true;
    }

    public boolean moveOut(String folderId, String app) {
        Folder folder = folder(folderId);
        if (folder == null || !folder.members().contains(app)) return false;
        int index = indexOf(folderId);
        List<String> members = new ArrayList<>(folder.members());
        members.remove(app);

        if (members.isEmpty()) {
            entries.set(index, new App(app));
        } else {
            entries.set(index, new Folder(folderId, folder.name(), members));
            entries.add(index + 1, new App(app));
        }
        return true;
    }

    /** Applies an external drag's landing point in the desktop order before extraction. */
    public int dropMember(String folderId, String app, int boundary, String target, String defaultName) {
        Folder folder = folder(folderId);
        if (folder == null || !folder.members().contains(app)) return -1;
        if (target == null) {
            if (boundary < 0 || boundary > entries.size()) return -1;
            int destination = boundary;
            if (folder.members().size() == 1 && indexOf(folderId) < boundary) destination--;
            moveOut(folderId, app);
            move(app, destination);
            return indexOf(app);
        }
        if (folderId.equals(target) || indexOf(target) < 0) return -1;
        if (folder(target) == null && validName(defaultName) == null) return -1;
        moveOut(folderId, app);
        int targetIndex = indexOf(target);
        int destination = indexOf(app) < targetIndex ? targetIndex - 1 : targetIndex;
        group(app, target, defaultName);
        return destination;
    }

    private static boolean isFolderId(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String validName(String raw) {
        if (raw == null) return null;
        String name = raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) return null;
        return name.codePoints().anyMatch(Character::isISOControl) ? null : name;
    }
}
