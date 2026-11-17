package com.november.mcphone.core.client.home;

/** Pointer state shared by the launcher and folders. Operates on stable IDs in device coordinates. */
public final class HomeInput {
    public record Release(String id, boolean dragged, boolean clicked) {}
    public record FolderDrag(String folderId, String appId) {}

    private final int dragThreshold;
    private final int swipeThreshold;
    private boolean pressed;
    private boolean dragging;
    private String id;
    private double startX;
    private double startY;
    private double x;
    private double y;

    public HomeInput(int dragThreshold, int swipeThreshold) {
        this.dragThreshold = dragThreshold;
        this.swipeThreshold = swipeThreshold;
    }

    public void press(String id, double x, double y) {
        pressed = true;
        dragging = false;
        this.id = id;
        startX = this.x = x;
        startY = this.y = y;
    }

    /** Takes over a drag that already crossed the source view's drag threshold. */
    public void continueDrag(String id, double x, double y) {
        press(id, x, y);
        dragging = true;
    }

    /** Returns a page step when a drag starts on a blank part of the launcher. */
    public int drag(double x, double y) {
        if (!pressed) return 0;
        this.x = x;
        this.y = y;

        double dx = x - startX;
        if (id != null) {
            dragging |= Math.abs(dx) >= dragThreshold || Math.abs(y - startY) >= dragThreshold;
        } else if (Math.abs(dx) >= swipeThreshold) {
            startX = x;
            return dx < 0 ? 1 : -1;
        }
        return 0;
    }

    public Release release(String hitId, double x, double y) {
        if (!pressed) return null;
        drag(x, y);
        Release result = new Release(id, dragging, !dragging && id != null && id.equals(hitId));
        cancel();
        return result;
    }

    public boolean pressed() { return pressed; }
    public String id() { return id; }
    public boolean dragging() { return dragging; }
    public double x() { return x; }
    public double y() { return y; }

    public void cancel() {
        pressed = false;
        dragging = false;
        id = null;
    }
}
