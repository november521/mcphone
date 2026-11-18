package com.november.mcphone.core.client.home;

import java.util.HashMap;
import java.util.Map;

/** Post-drop movement from the last visible desktop positions to the settled layout. */
public final class HomeReflow {
    public static final long DURATION_MS = 185;
    public static final HomeReflow NONE = new HomeReflow(Map.of());

    /** Top-left icon position in logical pixels. */
    public record Position(double x, double y) {}

    private final Map<String, Position> offsets;

    private HomeReflow(Map<String, Position> offsets) {
        this.offsets = offsets;
    }

    /**
     * Matches icons by stable ID. A new folder can inherit the position of the
     * app it replaces by supplying its ID and the former app ID in formerIds.
     */
    public static HomeReflow between(Map<String, Position> previous, Map<String, Position> next,
                                     Map<String, String> formerIds) {
        Map<String, Position> offsets = new HashMap<>();
        next.forEach((id, resting) -> {
            Position origin = previous.get(id);
            String formerId = formerIds.get(id);
            if (origin == null && formerId != null) origin = previous.get(formerId);
            if (origin != null && !origin.equals(resting)) {
                offsets.put(id, new Position(origin.x() - resting.x(), origin.y() - resting.y()));
            }
        });
        return offsets.isEmpty() ? NONE : new HomeReflow(Map.copyOf(offsets));
    }

    public boolean isEmpty() {
        return offsets.isEmpty();
    }

    /** Uses the same position for painting and hit-testing. */
    public Position position(String id, Position resting, long elapsedMs) {
        Position offset = offsets.get(id);
        if (offset == null || elapsedMs >= DURATION_MS) return resting;
        double remaining = elapsedMs <= 0 ? 1.0 : 1.0 - easing((double) elapsedMs / DURATION_MS);
        return new Position(resting.x() + offset.x() * remaining, resting.y() + offset.y() * remaining);
    }

    private static double easing(double progress) {
        // Invert the x component of the demo's cubic-bezier(.2, .72, .22, 1).
        double low = 0;
        double high = 1;
        for (int i = 0; i < 16; i++) {
            double t = (low + high) / 2;
            if (bezier(t, .2, .22) < progress) {
                low = t;
            } else {
                high = t;
            }
        }
        return bezier((low + high) / 2, .72, 1);
    }

    private static double bezier(double t, double first, double second) {
        double inverse = 1 - t;
        return 3 * inverse * inverse * t * first + 3 * inverse * t * t * second + t * t * t;
    }
}
