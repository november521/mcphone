package com.november.mcphone.core.client.home;

import com.november.mcphone.core.client.home.HomeReflow.Position;

import java.util.Map;

public final class HomeReflowTest {
    public static void main(String[] args) {
        stableIconsBeginAtTheirVisiblePositions();
        newFoldersBeginAtTheFormerTarget();
        interruptedReflowsStartFromTheSampledFrame();
        System.out.println("Home reflow tests passed");
    }

    private static void stableIconsBeginAtTheirVisiblePositions() {
        Position left = new Position(8, 12);
        Position right = new Position(108, 52);
        HomeReflow reflow = HomeReflow.between(Map.of("a", left, "b", right),
                Map.of("a", right, "b", left, "new", left), Map.of());
        equal(reflow.position("a", right, 0), left);
        equal(reflow.position("b", left, 0), right);
        Position midway = reflow.position("a", right, 92);
        if (midway.x() <= 100 || midway.x() >= 102 || midway.x() == Math.rint(midway.x())) {
            throw new AssertionError("Expected a fractional position near x=101, got " + midway);
        }
        equal(reflow.position("b", left, 185), left);
        equal(reflow.position("new", left, 0), left);
        if (!HomeReflow.between(Map.of("a", left), Map.of("a", left), Map.of()).isEmpty()) {
            throw new AssertionError("The desktop must not start a reflow when no icon moves");
        }
    }

    private static void newFoldersBeginAtTheFormerTarget() {
        Position oldTarget = new Position(64, 12);
        Position newFolder = new Position(36, 12);
        HomeReflow reflow = HomeReflow.between(Map.of("target", oldTarget),
                Map.of("folder", newFolder), Map.of("folder", "target"));
        equal(reflow.position("folder", newFolder, 0), oldTarget);
        equal(reflow.position("folder", newFolder, HomeReflow.DURATION_MS), newFolder);
    }

    private static void interruptedReflowsStartFromTheSampledFrame() {
        Position first = new Position(8, 12);
        Position second = new Position(64, 42);
        Position third = new Position(36, 12);
        HomeReflow original = HomeReflow.between(Map.of("app", first), Map.of("app", second), Map.of());
        Position visible = original.position("app", second, 35);
        HomeReflow interrupted = HomeReflow.between(Map.of("app", visible),
                Map.of("app", third), Map.of());
        equal(interrupted.position("app", third, 0), visible);
        equal(interrupted.position("app", third, HomeReflow.DURATION_MS), third);
    }

    private static void equal(Position actual, Position expected) {
        if (Math.abs(actual.x() - expected.x()) > 1e-9 || Math.abs(actual.y() - expected.y()) > 1e-9) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
