package dev.fzkc.fzhud.render;

/** Frame-rate independent smoothing (exponential ease-out toward a target). */
public class Anim {
    public float value;
    /** Higher = snappier. Adjustable at runtime from settings. */
    public float speed;
    private long last = System.nanoTime();

    public Anim(float initial, float speed) {
        this.value = initial;
        this.speed = speed;
    }

    public float update(float target) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, Math.max(0f, (now - last) / 1.0e9f));
        last = now;
        value += (target - value) * (1f - (float) Math.exp(-speed * dt));
        if (Math.abs(target - value) < 0.0005f) value = target;
        return value;
    }

    /** Smooth an angle in degrees, taking the shortest path around the circle. */
    public float updateAngle(float target) {
        float diff = ((target - value) % 360f + 540f) % 360f - 180f;
        update(value + diff);
        value = (value % 360f + 360f) % 360f;
        return value;
    }

    public void snap(float v) {
        value = v;
        last = System.nanoTime();
    }
}
