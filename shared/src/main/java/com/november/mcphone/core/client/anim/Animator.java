package com.november.mcphone.core.client.anim;

/**
 * 简单补间动画器。从 AtomChat 0.1.8 移植（MIT），原文件 {@code render/Animator.java}。
 *
 * 与 {@link UiMotion#approach(float, float, float, long)} 的区别：
 * approach 适合"朝目标渐近又永不超调"的连续值（hover、滚动条 alpha），
 * Animator 适合需要精确控制时长/起点/终点、并且"到达即钉死"的一次性补间
 * （页面转场、tab 指示器滑动）。
 */
public class Animator {
    @FunctionalInterface
    public interface Easing {
        float apply(float t);
    }

    private float start;
    private float end;
    private float lastEnd = Float.NaN;
    private float duration;
    private float timePassed;
    private float value;
    private boolean done = true;
    private final Easing easing;

    public Animator(Easing easing) {
        this.easing = easing;
    }

    public Animator animateTo(float durationMs, float target) {
        if (lastEnd != target || end != target || this.duration != durationMs) {
            start = value;
            duration = durationMs;
            end = target;
            lastEnd = target;
            timePassed = 0.0F;
            done = false;
        }
        return this;
    }

    public Animator setValue(float immediate) {
        start = immediate;
        end = immediate;
        value = immediate;
        lastEnd = immediate;
        timePassed = 0.0F;
        done = true;
        return this;
    }

    public void update(float deltaMs) {
        if (done) {
            return;
        }
        timePassed += deltaMs;
        if (timePassed >= duration) {
            timePassed = duration;
            value = end;
            done = true;
        } else {
            float progress = duration <= 0 ? 1.0F : timePassed / duration;
            value = start + (end - start) * easing.apply(progress);
        }
    }

    public float getValue() {
        return value;
    }

    public boolean isDone() {
        return done;
    }
}
