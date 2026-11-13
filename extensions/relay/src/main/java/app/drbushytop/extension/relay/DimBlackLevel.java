package app.drbushytop.extension.relay;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Relay's Night theme, made to look like Night (OLED), turns its blacks into dark grey while the
 * screen brightness is very low.
 *
 * <p>vivo's LTPO phones hold the screen at 60 Hz in dim light while half of it or more is darker
 * than 49/255, presumably because a slow refresh flickers there. Measured on a vivo X300 Ultra,
 * holding 60 Hz costs more battery than the grey's light does at that brightness. Above that
 * brightness the rate drops with black too, so the theme stays black.
 *
 * <p>The grey comes from a curve over the whole window: black becomes 51/255, darker tones are
 * lifted in proportion (so a dark toolbar stays apart from the background), and everything from
 * 102/255 up, text included, is left as it is.
 */
@SuppressWarnings("unused")
public final class DimBlackLevel {
    private static final String TAG = "RelayDimBlack";
    /** Set by the patch on the Night theme only. */
    private static final String MARKER_ATTR = "drbushytopLiftInDimLight";
    /** What black becomes: the darkest grey that lets the rate drop is 49; two steps of margin. */
    private static final float LIFTED = 51 / 255f;
    /**
     * The brightness setting (0 to 1) below which black holds 60 Hz: 0.023 does, 0.024 does not.
     * Lifting starts a little above it, where the grey costs nothing measurable, and stops a
     * little higher still, so the screen does not flip back and forth around one value.
     */
    private static final float LIFT_BELOW = 0.030f;
    private static final float UNLIFT_ABOVE = 0.036f;
    private static final long FADE_MS = 400;
    private static final String BRIGHTNESS = "screen_brightness_float";
    private static final String CURVE =
            "uniform shader content;"
            + "uniform half lift;"
            // Colours are premultiplied, so transparent parts of the window stay transparent.
            + "half4 main(float2 p) {"
            + "  half4 c = content.eval(p);"
            + "  return half4(max(c.rgb, c.a * lift + c.rgb * 0.5), c.a);"
            + "}";

    private static final WeakHashMap<Activity, Lift> LIFTS = new WeakHashMap<>();
    private static ContentResolver resolver;
    private static boolean dim;
    private static boolean installed;

    private DimBlackLevel() {
    }

    /** Hook: Relay's Application#onCreate. */
    public static void install(Application application) {
        try {
            if (application == null || installed || Build.VERSION.SDK_INT < 33) return;
            installed = true;
            resolver = application.getContentResolver();
            readBrightness();
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    readBrightness();
                    for (Lift lift : new ArrayList<>(LIFTS.values())) lift.update(true);
                }
            };
            resolver.registerContentObserver(Settings.System.getUriFor(BRIGHTNESS), false, observer);
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    readBrightness();
                    Lift lift = LIFTS.get(activity);
                    if (lift == null) {
                        if (!marked(activity)) return;
                        lift = new Lift(activity.getWindow().getDecorView());
                        LIFTS.put(activity, lift);
                    }
                    // Coming back to the screen: no need to fade.
                    lift.update(false);
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    Lift lift = LIFTS.remove(activity);
                    if (lift != null) lift.cancel();
                }

                @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            });
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** Whether the activity uses the Night theme. */
    private static boolean marked(Activity activity) {
        try {
            if (activity.getWindow() == null) return false;
            int attr = activity.getResources().getIdentifier(MARKER_ATTR, "attr", activity.getPackageName());
            if (attr == 0) return false;
            TypedValue value = new TypedValue();
            return activity.getTheme().resolveAttribute(attr, value, true)
                    && value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0;
        } catch (Throwable t) {
            Log.e(TAG, "marked", t);
            return false;
        }
    }

    private static void readBrightness() {
        try {
            float brightness;
            try {
                brightness = Settings.System.getFloat(resolver, BRIGHTNESS);
            } catch (Throwable unreadable) {
                // Only the 0 to 255 setting: 0.030 is between 7 and 8.
                brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) / 255f;
            }
            dim = dim ? brightness < UNLIFT_ABOVE : brightness < LIFT_BELOW;
        } catch (Throwable t) {
            dim = false;
        }
    }

    /** The curve over one activity's window, faded in and out. */
    private static final class Lift {
        private final View decor;
        private final RuntimeShader shader = new RuntimeShader(CURVE);
        /** 0 is no change, {@link #LIFTED} is fully lifted. */
        private float shown;
        private ValueAnimator fade;

        Lift(View decor) {
            this.decor = decor;
        }

        void update(boolean animate) {
            float target = dim ? LIFTED : 0f;
            if (fade != null) {
                fade.cancel();
                fade = null;
            }
            if (shown == target) return;
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "lifted blacks: " + dim);
            if (!animate) {
                apply(target);
                return;
            }
            fade = ValueAnimator.ofFloat(shown, target);
            fade.setDuration(FADE_MS);
            fade.addUpdateListener(animation -> apply((Float) animation.getAnimatedValue()));
            fade.start();
        }

        void cancel() {
            if (fade != null) fade.cancel();
        }

        private void apply(float lift) {
            try {
                shown = lift;
                if (lift <= 0f) {
                    decor.setRenderEffect(null);
                    return;
                }
                shader.setFloatUniform("lift", lift);
                decor.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            } catch (Throwable t) {
                Log.e(TAG, "apply", t);
            }
        }
    }
}
