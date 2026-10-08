import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * Plays a two-finger gesture on a test phone: `input` sends one finger only, and writing to
 * /dev/input is denied to the shell.
 *
 *   scripts/touch/two-finger.sh x1a y1a x1b y1b x2a y2a x2b y2b [steps] [stepMs] [holdMs] [repeat] [cycles]
 *
 * Finger 1 goes from (x1a,y1a) to (x1b,y1b) and finger 2 from (x2a,y2a) to (x2b,y2b) in [steps]
 * moves [stepMs] apart, both stay down for [holdMs], then lift. Pixels of the screen.
 *
 * With [cycles], one run plays a sweep that many times: the gesture [repeat] times, then its
 * reverse [repeat] times. A frame rate test uses this so no new process starts mid-measurement.
 */
public class TwoFinger {
    static Object im; static Method inject;
    public static void main(String[] a) throws Exception {
        float[] v = new float[8]; for (int i = 0; i < 8; i++) v[i] = Float.parseFloat(a[i]);
        int n = a.length > 8 ? Integer.parseInt(a[8]) : 25;
        long ms = a.length > 9 ? Long.parseLong(a[9]) : 16;
        long hold = a.length > 10 ? Long.parseLong(a[10]) : 250;
        Class<?> c;
        try { c = Class.forName("android.hardware.input.InputManagerGlobal"); } catch (Throwable t) { c = Class.forName("android.hardware.input.InputManager"); }
        im = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        int repeat = a.length > 11 ? Integer.parseInt(a[11]) : 1;
        int cycles = a.length > 12 ? Integer.parseInt(a[12]) : 0;
        if (cycles <= 0) { for (int r = 0; r < repeat; r++) play(v, n, ms, hold); return; }
        float[] back = { v[2], v[3], v[0], v[1], v[6], v[7], v[4], v[5] };
        for (int k = 0; k < cycles; k++) {
            for (int r = 0; r < repeat; r++) { play(v, n, ms, hold); Thread.sleep(120); }
            for (int r = 0; r < repeat; r++) { play(back, n, ms, hold); Thread.sleep(120); }
        }
    }

    static void play(float[] v, int n, long ms, long hold) throws Exception {
        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, 1, v[0], v[1], v[4], v[5]);
        send(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, v[0], v[1], v[4], v[5]);
        float x1 = v[0], y1 = v[1], x2 = v[4], y2 = v[5];
        for (int i = 1; i <= n; i++) {
            float f = i / (float) n;
            x1 = v[0] + (v[2] - v[0]) * f; y1 = v[1] + (v[3] - v[1]) * f;
            x2 = v[4] + (v[6] - v[4]) * f; y2 = v[5] + (v[7] - v[5]) * f;
            Thread.sleep(ms);
            send(down, MotionEvent.ACTION_MOVE, 2, x1, y1, x2, y2);
        }
        Thread.sleep(hold);
        send(down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, x1, y1, x2, y2);
        send(down, MotionEvent.ACTION_UP, 1, x1, y1, x2, y2);
    }

    static void send(long down, int action, int count, float x1, float y1, float x2, float y2) throws Exception {
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            pp[i] = new MotionEvent.PointerProperties(); pp[i].id = i; pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords(); pc[i].x = i == 0 ? x1 : x2; pc[i].y = i == 0 ? y1 : y2; pc[i].pressure = 1f; pc[i].size = 1f;
        }
        MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject.invoke(im, ev, 2);
    }
}
