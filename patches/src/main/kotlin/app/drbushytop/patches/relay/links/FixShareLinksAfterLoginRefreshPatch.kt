package app.drbushytop.patches.relay.links

import app.drbushytop.patches.relay.Relay
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private const val ACCESS_TOKEN_CLASS = "Lreddit/news/oauth/reddit/model/RedditAccessToken;"
private const val SHARE_LINK_LOGIN_CLASS = "Lapp/drbushytop/extension/relay/ShareLinkLogin;"

/** `RedditAccessToken#updateToken(RedditAccessToken)`: stores a refreshed login. */
internal object UpdateTokenFingerprint : Fingerprint(
    definingClass = ACCESS_TOKEN_CLASS,
    name = "updateToken",
    returnType = "V",
    parameters = listOf(ACCESS_TOKEN_CLASS),
)

/** `RedditAccessToken(Parcel)`: restores a login, for example when an activity is recreated. */
internal object TokenFromParcelFingerprint : Fingerprint(
    definingClass = ACCESS_TOKEN_CLASS,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/os/Parcel;"),
)

@Suppress("unused")
val fixShareLinksAfterLoginRefreshPatch = bytecodePatch(
    name = "Fix share links after login refresh",
    description = "Makes patcheddit's share link fix (reddit.com/r/.../s/...) keep working once Relay " +
        "has renewed its login. Until now, links then failed with \"Failed to resolve\" and opened in " +
        "the browser, because patcheddit sent Reddit the token Relay renews its login with, which " +
        "Reddit blocks. Does nothing without patcheddit's patch.",
    default = true,
) {
    compatibleWith(*Relay.compatibility)
    extendWith(Relay.EXTENSION)

    execute {
        // Patcheddit hooks where these methods store the refresh token, so a call at each
        // return runs after it, whichever bundle's patches run first.
        listOf(UpdateTokenFingerprint, TokenFromParcelFingerprint).forEach { fingerprint ->
            fingerprint.method.apply {
                val returns = instructions.withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }
                    .map { it.index }
                if (returns.isEmpty()) throw PatchException("${fingerprint.method.name} has no return-void")
                returns.reversed().forEach { index ->
                    addInstruction(index, "invoke-static { }, $SHARE_LINK_LOGIN_CLASS->clear()V")
                }
            }
        }
    }
}
