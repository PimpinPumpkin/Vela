// A mock drive for a phone on adb: fixes with speed and bearing. `cmd location providers
// set-test-provider-location` takes neither, and a fix without them leaves the dot parked and
// the speed at 0, so driving mode's alerts never run. This feeds a test provider through
// ILocationManager as the shell user, which may set mock locations once allowed.
//
// Build (JDK 17 and the SDK's d8):
//   javac -source 11 -target 11 -cp $SDK/platforms/android-35/android.jar -d out MockDrive.java
//   $SDK/build-tools/36.0.0/d8 --output dex out/MockDrive.class
//   adb push dex/classes.dex /data/local/tmp/mockdrive.dex
// Run (the provider must exist, and the shell must be allowed to mock):
//   adb shell appops set com.android.shell android:mock_location allow
//   adb shell cmd location providers add-test-provider gps --supportsSpeed --supportsBearing
//   adb shell cmd location providers set-test-provider-enabled gps true
//   adb shell CLASSPATH=/data/local/tmp/mockdrive.dex app_process / MockDrive /data/local/tmp/drive.txt
// drive.txt: one fix per line, lat,lon,speedMps,bearingDeg[,sleepMs], sent in order, 1 s apart
// by default. Afterwards remove the provider, set the appop back to default and check that
// `dumpsys location | grep -ci mock` prints 0. A provider left behind has crashed system_server
// later; reboot only if it will not go away.
import android.location.Location;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

/** Feeds a test provider fixes WITH speed and bearing (the shell's cmd location cannot), as the
 *  shell user through ILocationManager. Lines: lat,lon,speedMps,bearingDeg[,sleepMs]. */
public class MockDrive {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "location");
        Object ilm = Class.forName("android.location.ILocationManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
        Method set = null;
        for (Method m : ilm.getClass().getMethods()) if (m.getName().equals("setTestProviderLocation")) { set = m; break; }
        if (set == null) throw new RuntimeException("no setTestProviderLocation");
        int argc = set.getParameterTypes().length;
        String provider = args.length > 1 ? args[1] : "gps";
        BufferedReader r = new BufferedReader(new FileReader(args[0]));
        String line; int n = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split(",");
            Location loc = new Location(provider);
            loc.setLatitude(Double.parseDouble(p[0]));
            loc.setLongitude(Double.parseDouble(p[1]));
            loc.setSpeed(Float.parseFloat(p[2]));
            loc.setBearing(Float.parseFloat(p[3]));
            loc.setAccuracy(5f);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            long sleep = p.length > 4 ? Long.parseLong(p[4]) : 1000L;
            Object[] a = argc == 4 ? new Object[]{provider, loc, "com.android.shell", null} : new Object[]{provider, loc, "com.android.shell"};
            set.invoke(ilm, a);
            n++;
            System.out.println(n + " " + p[0] + "," + p[1] + " v=" + p[2] + " b=" + p[3]);
            Thread.sleep(sleep);
        }
    }
}
