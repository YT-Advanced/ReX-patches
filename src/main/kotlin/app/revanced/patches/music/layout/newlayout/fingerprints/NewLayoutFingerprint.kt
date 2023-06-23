package app.revanced.patches.music.layout.newlayout.fingerprints

import app.revanced.patcher.fingerprint.method.impl.MethodFingerprint
import app.revanced.util.bytecode.isWide32LiteralExists

object NewLayoutFingerprint : MethodFingerprint(
    returnType = "Z",
    parameters = listOf(),
    customFingerprint = { methodDef, _ -> methodDef.isWide32LiteralExists(45399578) }
)