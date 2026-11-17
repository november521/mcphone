package com.november.mcphone.core.client.home;

import java.util.Objects;

public final class HomeInputTest {
    public static void main(String[] args) {
        HomeInput input = new HomeInput(3, 24);

        input.press("folder", 10, 10);
        equal(input.release("folder", 12, 10), new HomeInput.Release("folder", false, true));

        input.press("app", 10, 10);
        equal(input.release("other", 11, 10), new HomeInput.Release("app", false, false));

        input.press("app", 10, 10);
        equal(input.drag(10, 13), 0);
        equal(input.dragging(), true);
        equal(input.release("app", 10, 10), new HomeInput.Release("app", true, false));

        input.press("app", 10, 10);
        equal(input.release(null, 15, 10), new HomeInput.Release("app", true, false));

        input.press(null, 60, 10);
        equal(input.drag(37, 0), 0);
        equal(input.drag(36, 0), 1);
        equal(input.drag(36, 0), 0);
        equal(input.drag(60, 0), -1);
        equal(input.release("app", 60, 0), new HomeInput.Release(null, false, false));

        input.press("app", 10, 10);
        input.cancel();
        equal(input.release("app", 10, 10), null);

        input.continueDrag("app", 65, 20);
        equal(input.dragging(), true);
        equal(input.x(), 65.0);
        equal(input.y(), 20.0);
        equal(input.release("app", 65, 20), new HomeInput.Release("app", true, false));
        System.out.println("Home input tests passed");
    }

    private static void equal(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
