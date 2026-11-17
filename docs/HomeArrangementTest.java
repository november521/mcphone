package com.november.mcphone.core.client.home;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class HomeArrangementTest {
    private static final String A = "mcphone:a";
    private static final String B = "mcphone:b";
    private static final String C = "mcphone:c";
    private static final String D = "mcphone:d";

    public static void main(String[] args) {
        legacyOrder();
        folderOperations();
        dropsFolderMembersAtFinalDestination();
        restoresMixedEntriesWithoutDuplicates();
        System.out.println("Home arrangement tests passed");
    }

    private static void legacyOrder() {
        HomeArrangement home = new HomeArrangement();
        home.restore(List.of(B, C, A), null);
        equal(home.entries(), List.of(new HomeArrangement.App(B), new HomeArrangement.App(C), new HomeArrangement.App(A)));
        check(home.move(B, 2), "Home reorder");
        equal(home.appsInOrder(), List.of(C, A, B));
    }

    private static void folderOperations() {
        HomeArrangement home = new HomeArrangement();
        home.restore(List.of(A, B, C, D), null);
        check(home.group(A, C, "Folder"), "Create folder");
        equal(home.entries().get(0), new HomeArrangement.App(B));
        HomeArrangement.Folder folder = (HomeArrangement.Folder) home.entries().get(1);
        String id = folder.id();
        equal(folder.members(), List.of(C, A));

        check(!home.group(id, B, "Folder"), "No nested folders");
        check(home.group(D, id, "Ignored"), "Drag app into folder");
        check(home.rename(id, "  Games  "), "Rename");
        check(!home.rename(id, "  "), "Reject blank name");
        check(home.add(id, List.of(B, C, id, B)), "Picker only adds ungrouped apps");
        equal(home.folder(id).name(), "Games");
        equal(home.folder(id).members(), List.of(C, A, D, B));

        check(home.moveMember(id, B, 0), "Reorder members");
        equal(home.appsInOrder(), List.of(B, C, A, D));
        check(home.moveOut(id, C), "Move member to home");
        equal(home.entries().get(1), new HomeArrangement.App(C));

        home.reconcile(List.of(A, B, C));
        equal(home.folder(id).members(), List.of(B, A));
        home.reconcile(List.of(A, C));
        equal(home.folder(id).members(), List.of(A));
        check(home.moveOut(id, A), "Move last member to home");
        equal(home.folder(id), null);
        equal(home.appsInOrder(), List.of(A, C));
    }

    private static void restoresMixedEntriesWithoutDuplicates() {
        String folder = "c5bbdd59-a5ee-4ea9-b1bf-f196720782c7";
        String duplicate = "dd2ffd0e-a7e9-4a6f-8ff2-4516259035df";
        List<HomeArrangement.StoredEntry> stored = new ArrayList<>();
        stored.add(new HomeArrangement.StoredEntry(B, null, null));
        stored.add(new HomeArrangement.StoredEntry(folder, "Saved", Arrays.asList(B, A, A, null, "gone")));
        stored.add(new HomeArrangement.StoredEntry(duplicate, "Empty", List.of(B, "gone")));
        stored.add(new HomeArrangement.StoredEntry(folder, "Duplicate", List.of(C)));
        stored.add(new HomeArrangement.StoredEntry("invalid", "Bad ID", List.of(D)));
        stored.add(null);
        HomeArrangement home = new HomeArrangement();
        home.restore(List.of(A, C, D, B), stored);
        equal(home.appsInOrder(), List.of(B, A, C, D));
        equal(home.folder(folder).members(), List.of(A));
        equal(home.folder(duplicate), null);

        Gson gson = new Gson();
        String json = gson.toJson(home.snapshot());
        HomeArrangement copy = new HomeArrangement();
        copy.restore(home.appsInOrder(), Arrays.asList(gson.fromJson(json, HomeArrangement.StoredEntry[].class)));
        equal(copy.entries(), home.entries());
        copy.reconcile(List.of(B, C, D));
        equal(copy.folder(folder), null);
        equal(copy.appsInOrder(), List.of(B, C, D));
    }

    private static void dropsFolderMembersAtFinalDestination() {
        HomeArrangement home = new HomeArrangement();
        home.restore(List.of(A, B, C, D), null);
        check(home.group(A, B, "Source"), "Create source folder");
        String source = home.entries().get(0).id();
        var previous = home.snapshot();
        equal(home.dropMember(source, A, -1, source, "Folder"), -1);
        equal(home.dropMember(source, A, -1, null, "Folder"), -1);
        equal(home.dropMember(source, A, -1, D, " "), -1);
        equal(home.snapshot(), previous);

        equal(home.dropMember(source, A, 3, null, "Folder"), 3);
        equal(home.entries().get(3), new HomeArrangement.App(A));
        equal(home.folder(source).members(), List.of(B));
        equal(home.dropMember(source, B, 4, null, "Folder"), 3);
        equal(home.folder(source), null);
        equal(home.appsInOrder(), List.of(C, D, A, B));

        check(home.group(A, B, "Source"), "Recreate source folder");
        source = home.entries().get(2).id();
        int destination = home.dropMember(source, A, -1, C, "Target");
        equal(destination, 0);
        var target = (HomeArrangement.Folder) home.entries().get(destination);
        equal(target.members(), List.of(C, A));
        equal(home.dropMember(source, B, -1, target.id(), "Folder"), 0);
        equal(home.folder(target.id()).members(), List.of(C, A, B));
        equal(home.folder(source), null);
    }

    private static void equal(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
