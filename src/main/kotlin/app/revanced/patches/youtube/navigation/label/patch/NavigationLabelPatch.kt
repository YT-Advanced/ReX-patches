package app.revanced.patches.youtube.navigation.label.patch

import app.revanced.extensions.toErrorResult
import app.revanced.patcher.annotation.Description
import app.revanced.patcher.annotation.Name
import app.revanced.patcher.annotation.Version
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.patch.PatchResultSuccess
import app.revanced.patcher.patch.annotations.DependsOn
import app.revanced.patcher.patch.annotations.Patch
import app.revanced.patches.youtube.navigation.label.fingerprints.PivotBarSetTextFingerprint
import app.revanced.patches.youtube.utils.annotations.YouTubeCompatibility
import app.revanced.patches.youtube.utils.settings.resource.patch.SettingsPatch
import app.revanced.util.integrations.Constants.NAVIGATION
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction

@Patch
@Name("hide-navigation-label")
@Description("Hide navigation bar labels.")
@DependsOn([SettingsPatch::class])
@YouTubeCompatibility
@Version("0.0.1")
class NavigationLabelPatch : BytecodePatch(
    listOf(PivotBarSetTextFingerprint)
) {
    override fun execute(context: BytecodeContext): PatchResult {

        PivotBarSetTextFingerprint.result?.let {
            it.mutableMethod.apply {
                val targetIndex = it.scanResult.patternScanResult!!.endIndex - 2
                val targetReference =
                    getInstruction<ReferenceInstruction>(targetIndex).reference.toString()
                val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                if (targetReference != "Landroid/widget/TextView;")
                    return PivotBarSetTextFingerprint.toErrorResult()

                addInstruction(
                    targetIndex + 1,
                    "invoke-static {v$targetRegister}, $NAVIGATION->hideNavigationLabel(Landroid/widget/TextView;)V"
                )
            }
        } ?: return PivotBarSetTextFingerprint.toErrorResult()

        /**
         * Add settings
         */
        SettingsPatch.addPreference(
            arrayOf(
                "PREFERENCE: NAVIGATION_SETTINGS",
                "SETTINGS: HIDE_NAVIGATION_LABEL"
            )
        )

        SettingsPatch.updatePatchStatus("hide-navigation-label")

        return PatchResultSuccess()
    }
}
