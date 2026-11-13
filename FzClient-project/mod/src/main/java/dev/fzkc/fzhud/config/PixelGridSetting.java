package dev.fzkc.fzhud.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A square of on/off pixels (a hand-made crosshair). Saved as one line of '0' and '1', row by row. The size is odd
 * so there is a centre pixel.
 */
public class PixelGridSetting extends Setting {
    public final int size;
    public final boolean[] cells;
    private final boolean[] def;

    public PixelGridSetting(String key, String label, int size) {
        super(key, label);
        this.size = size;
        this.cells = new boolean[size * size];
        this.def = new boolean[size * size];
        // a small cross with a gap, so the editor never starts empty
        int c = size / 2;
        for (int d = 2; d <= 4; d++) {
            def[c * size + c - d] = def[c * size + c + d] = true;
            def[(c - d) * size + c] = def[(c + d) * size + c] = true;
        }
        reset();
    }

    public boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && cells[y * size + x];
    }

    public void set(int x, int y, boolean on) {
        if (x >= 0 && y >= 0 && x < size && y < size) cells[y * size + x] = on;
    }

    public boolean empty() {
        for (boolean b : cells) if (b) return false;
        return true;
    }

    @Override
    public JsonElement save() {
        StringBuilder sb = new StringBuilder(cells.length);
        for (boolean b : cells) sb.append(b ? '1' : '0');
        return new JsonPrimitive(sb.toString());
    }

    @Override
    public void load(JsonElement e) {
        try {
            String s = e.getAsString();
            if (s.length() != cells.length) return;   // another size: keep the default
            for (int i = 0; i < cells.length; i++) cells[i] = s.charAt(i) == '1';
        } catch (Exception ignored) {
        }
    }

    @Override
    public void reset() {
        System.arraycopy(def, 0, cells, 0, cells.length);
    }
}
