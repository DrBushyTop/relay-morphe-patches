package app.drbushytop.extension.relay;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * Works around patcheddit's "Fix /s/ links" in Relay.
 *
 * <p>To resolve a share link (reddit.com/r/.../s/...), patcheddit asks Reddit for it with the
 * login it was given as a bearer token and follows the redirect. But it is given the value Relay
 * stores in RedditAccessToken's {@code refreshToken} field: empty when Relay starts, and the
 * refresh token once Relay renews its login. Reddit blocks requests that send the refresh token
 * (403, no redirect), so from then on every share link failed and opened in the browser.
 *
 * <p>Right after patcheddit stores it, its copy is set back to empty, which Reddit resolves like
 * a request without a login, as it does right after Relay starts.
 */
@SuppressWarnings("unused")
public final class ShareLinkLogin {
    private static final String TAG = "RelayShareLinks";
    private static final String S_LINKS_CLASS =
            "app.morphe.extension.shared.fixes.slink.BaseFixSLinksPatch";

    private static Field instanceField;
    private static Field tokenField;
    private static boolean unavailable;

    private ShareLinkLogin() {
    }

    /** Hook: the end of RedditAccessToken#updateToken and RedditAccessToken(Parcel). */
    public static synchronized void clear() {
        if (unavailable) return;
        try {
            if (tokenField == null) {
                Class<?> sLinks = Class.forName(S_LINKS_CLASS, true,
                        ShareLinkLogin.class.getClassLoader());
                instanceField = sLinks.getDeclaredField("INSTANCE");
                instanceField.setAccessible(true);
                tokenField = sLinks.getDeclaredField("accessToken");
                tokenField.setAccessible(true);
            }
            Object patch = instanceField.get(null);
            // Null means patcheddit has no login yet and waits for one: leave that as it is.
            if (patch == null || tokenField.get(patch) == null) return;
            tokenField.set(patch, "");
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Cleared the /s/ link login");
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            unavailable = true; // Patched without patcheddit's /s/ link fix, or it changed.
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Patcheddit's /s/ link fix not found", e);
        } catch (Throwable t) {
            Log.e(TAG, "clear", t);
        }
    }
}
