ppackage app.revanced.patches.youtube.utils.sponsorblock.fingerprints

import app.revanced.patcher.extensions.or
import app.revanced.patcher.fingerprint.MethodFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object TimelineMarkerArrayFingerprint : MethodFingerprint(
    returnType = "[Lcom/google/android/libraries/youtube/player/features/overlay/timebar/TimelineMarker;",
    accessFlags = AccessFlags.PUBLIC or AccessFlags.FINAL,
    opcodes = listOf(
        Opcode.NEW_ARRAY,
        Opcode.RETURN_OBJECT,
    ),
)