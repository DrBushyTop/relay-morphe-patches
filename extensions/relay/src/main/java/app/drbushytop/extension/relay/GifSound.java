package app.drbushytop.extension.relay;

import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lets Relay play Redgifs posts from Redgifs itself, with sound, and fall back to Reddit's silent
 * copy of the clip when Redgifs can't serve it (removed, or the API is down).
 *
 * <p>Relay only asks the Redgifs API when its remote config flag {@code redgifs_api_enabled} is on.
 * Patcheddit turns remote config off, so Relay always played Reddit's copy
 * ({@code preview.reddit_video_preview}), which has no sound.
 */
@SuppressWarnings("unused")
public final class GifSound {
    private static final String TAG = "RelayGifSound";

    /** Posts whose Redgifs clip Relay has been told to load from Redgifs. */
    private static final Set<Object> FROM_REDGIFS = Collections.newSetFromMap(new WeakHashMap<>());
    /** Posts Redgifs failed for, which now get Reddit's copy. */
    private static final Set<Object> FELL_BACK = Collections.newSetFromMap(new WeakHashMap<>());

    private GifSound() {
    }

    /**
     * Hook: stands in for Relay's {@code redgifs_api_enabled} flag while it builds a Redgifs post's
     * media. Relay reads the flag only for Redgifs links.
     *
     * @return whether to ask Redgifs for the clip; false plays Reddit's copy.
     */
    public static boolean loadFromRedgifs(Object link) {
        synchronized (GifSound.class) {
            if (FELL_BACK.contains(link)) return false;
            FROM_REDGIFS.add(link);
            return true;
        }
    }

    /**
     * Hook: the start of Relay's error handler for loading a post's media.
     *
     * @return null when the post was a Redgifs clip that failed to load for the first time: Relay
     * should then load the post's media again, which now uses Reddit's copy. Otherwise
     * {@code error}, for Relay to handle as before.
     */
    public static Throwable retryWithRedditCopy(Object link, Throwable error) {
        synchronized (GifSound.class) {
            if (!FROM_REDGIFS.remove(link) || !FELL_BACK.add(link)) return error;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Redgifs failed, using Reddit's copy", error);
        return null;
    }
}
