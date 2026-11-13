package app.drbushytop.patches.relay.media

import app.drbushytop.patches.relay.Relay
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val GIF_SOUND_CLASS = "Lapp/drbushytop/extension/relay/GifSound;"
private const val REDDIT_LINK_CLASS = "Lreddit/news/oauth/reddit/model/links/RedditLink;"
private const val MEDIA_URL_FETCHER_CLASS = "Lreddit/news/previews/MediaUrlFetcher;"

/**
 * `RedditLink#fetchMediaPreviews(MediaUrlFetcher, Scheduler)`. For a redgifs.com/watch link it
 * reads Relay's remote config flag `redgifs_api_enabled` (`RemoteConfigManageBase#e`). While the
 * flag is off, Relay plays Reddit's own copy of the clip (`preview.reddit_video_preview`), which
 * has no sound, instead of asking the Redgifs API for the original.
 */
internal object FetchMediaPreviewsFingerprint : Fingerprint(
    definingClass = REDDIT_LINK_CLASS,
    name = "fetchMediaPreviews",
    returnType = "V",
    parameters = listOf(MEDIA_URL_FETCHER_CLASS, "L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = MEDIA_URL_FETCHER_CLASS,
            type = "Lreddit/news/remoteconfig/RemoteConfigManager;",
        ),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, name = "e", type = "Z"),
    ),
)

/**
 * The error handler of the load `fetchMediaPreviews` starts: it prints the error and falls back
 * to the post's thumbnails.
 */
internal object FetchMediaPreviewsErrorFingerprint : Fingerprint(
    definingClass = REDDIT_LINK_CLASS,
    name = "lambda\$fetchMediaPreviews\$2",
    returnType = "V",
    parameters = listOf(MEDIA_URL_FETCHER_CLASS, "Ljava/lang/Throwable;"),
)

@Suppress("unused")
val fixGifSoundPatch = bytecodePatch(
    name = "Fix GIF sound",
    description = "Plays GIF videos from the site that hosts them, with sound, instead of Reddit's " +
        "silent copy. When the original has been removed, Reddit's copy plays as before.",
    default = true,
) {
    compatibleWith(*Relay.compatibility)
    extendWith(Relay.EXTENSION)

    execute {
        FetchMediaPreviewsFingerprint.method.apply {
            val index = FetchMediaPreviewsFingerprint.instructionMatches.last().index
            // iget-boolean has a 4-bit destination, so the register fits move-result.
            val register = getInstruction<TwoRegisterInstruction>(index).registerA
            replaceInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $GIF_SOUND_CLASS->loadFromRedgifs(Ljava/lang/Object;)Z",
            )
            addInstruction(index + 1, "move-result v$register")
        }

        // p2 (the Throwable) comes back unchanged unless the post should be loaded again, so
        // Relay's own handling still sees it; no scratch register is needed.
        FetchMediaPreviewsErrorFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                invoke-static { p0, p2 }, $GIF_SOUND_CLASS->retryWithRedditCopy(Ljava/lang/Object;Ljava/lang/Throwable;)Ljava/lang/Throwable;
                move-result-object p2
                if-nez p2, :original
                invoke-virtual { p0, p1 }, $REDDIT_LINK_CLASS->fetchMediaPreviews($MEDIA_URL_FETCHER_CLASS)V
                return-void
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }
    }
}
