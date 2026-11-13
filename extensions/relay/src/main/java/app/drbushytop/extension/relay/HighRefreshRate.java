package app.drbushytop.extension.relay;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Asks for the display's highest refresh rate in every Relay window. Relay never asks, and on
 * adaptive refresh rate phones the system then keeps it at 60 Hz, scrolling included.
 *
 * <p>Same approach as patcheddit's "Prefer high refresh rate" patch for Boost: a window refresh
 * rate preference, plus on Android 15 and later a high frame rate category on the window's views,
 * which only counts while they are drawing.
 */
@SuppressWarnings("unused")
public final class HighRefreshRate {
    private static final String TAG = "RelayRefreshRate";
    private static boolean installed;

    private HighRefreshRate() {
    }

    /** Hook: Relay's Application#onCreate. */
    public static void install(Application application) {
        try {
            if (application == null || installed) return;
            installed = true;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    apply(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    apply(activity);
                }

                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    private static void apply(final Activity activity) {
        applyNow(activity);
        try {
            // The window may not be on a display yet while the activity is being created.
            View decor = activity.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override
                public void run() {
                    applyNow(activity);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "apply", t);
        }
    }

    private static void applyNow(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            float highest = highestRefreshRate(activity);
            boolean debug = Log.isLoggable(TAG, Log.DEBUG);
            if (debug) Log.d(TAG, activity.getClass().getSimpleName() + ": highest refresh rate " + highest);
            if (highest <= 0f) return;

            WindowManager.LayoutParams attributes = window.getAttributes();
            if (Math.abs(attributes.preferredRefreshRate - highest) > 0.01f) {
                attributes.preferredRefreshRate = highest;
                window.setAttributes(attributes);
            }
            if (Build.VERSION.SDK_INT >= 35) {
                window.getDecorView().setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyNow", t);
        }
    }

    /** The highest refresh rate the activity's display offers at its current resolution. */
    @SuppressWarnings("deprecation")
    private static float highestRefreshRate(Activity activity) {
        Display display = Build.VERSION.SDK_INT >= 30
                ? activity.getDisplay()
                : activity.getWindowManager().getDefaultDisplay();
        if (display == null) return 0f;
        Display.Mode current = display.getMode();
        float highest = 0f;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                highest = Math.max(highest, mode.getRefreshRate());
            }
        }
        return highest;
    }
}
