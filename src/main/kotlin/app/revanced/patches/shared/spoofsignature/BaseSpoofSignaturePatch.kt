package app.revanced.patches.shared.spoofsignature

import app.revanced.patcher.PatchClass
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patches.shared.gms.BaseGmsCoreSupportPatch
import app.revanced.patches.shared.spoofsignature.fingerprints.CertificateFingerprint
import app.revanced.patches.shared.spoofsignature.fingerprints.CertificateFingerprint.GET_PACKAGE_NAME_METHOD_REFERENCE
import app.revanced.patches.shared.spoofsignature.fingerprints.SpoofSignatureFingerprint
import app.revanced.patches.shared.spoofsignature.fingerprints.SpoofSignatureFingerprint.GET_ORIGINAL_PACKAGE_NAME_METHOD_NAME
import app.revanced.patches.shared.spoofsignature.fingerprints.SpoofSignatureFingerprint.INTEGRATIONS_CLASS_DESCRIPTOR
import app.revanced.util.getTargetIndexWithReference
import app.revanced.util.resultOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

/**
 * Spoof the package name used for app signature verification in Android 12+.
 * It has not been checked whether it can be used in other Google apps such as Google Maps or Google News,
 * So patch is not included in [BaseGmsCoreSupportPatch].
 */
abstract class BaseSpoofSignaturePatch(
    private val packageName: String,
    dependencies: Set<PatchClass> = emptySet()
) : BytecodePatch(
    description = "Spoofs the package name used for app signature verification in Android 12+.",
    fingerprints = setOf(
        CertificateFingerprint,
        SpoofSignatureFingerprint,
    ),
    dependencies = dependencies
) {
    override fun execute(context: BytecodeContext) {

        // Spoof signature.
        CertificateFingerprint.resultOrThrow().mutableClass.methods.forEach { mutableMethod ->
            mutableMethod.apply {
                val getPackageNameIndex =
                    getTargetIndexWithReference(GET_PACKAGE_NAME_METHOD_REFERENCE)

                if (getPackageNameIndex > -1) {
                    val targetRegister = (getInstruction(getPackageNameIndex) as FiveRegisterInstruction).registerC

                    replaceInstruction(
                        getPackageNameIndex,
                        "invoke-static {v$targetRegister}, $INTEGRATIONS_CLASS_DESCRIPTOR->spoofPackageName(Landroid/content/Context;)Ljava/lang/String;",
                    )
                }
            }
        }

        // Change the original package name in ReVanced Integrations.
        SpoofSignatureFingerprint.resultOrThrow().mutableClass.methods
            .single { it.name == GET_ORIGINAL_PACKAGE_NAME_METHOD_NAME }
            .replaceInstruction(0, "const-string v0, \"$packageName\"")

    }
}