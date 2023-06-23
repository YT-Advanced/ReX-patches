package app.revanced.patches.youtube.general.snackbar.patch

import app.revanced.extensions.toErrorResult
import app.revanced.patcher.annotation.Description
import app.revanced.patcher.annotation.Name
import app.revanced.patcher.annotation.Version
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.patch.PatchResultSuccess
import app.revanced.patcher.patch.annotations.DependsOn
import app.revanced.patcher.patch.annotations.Patch
import app.revanced.patcher.util.smali.ExternalLabel
import app.revanced.patches.youtube.general.snackbar.fingerprints.HideSnackBarFingerprint
import app.revanced.patches.youtube.utils.annotations.YouTubeCompatibility
import app.revanced.patches.youtube.utils.settings.resource.patch.SettingsPatch
import app.revanced.util.integrations.Constants.GENERAL

@Patch
@Name("hide-snack-bar")
@Description("Hides the snack bar action popup.")
@DependsOn([SettingsPatch::class])
@YouTubeCompatibility
@Version("0.0.1")
class HideSnackBarPatch : BytecodePatch(
    listOf(HideSnackBarFingerprint)
) {
    override fun execute(context: BytecodeContext): PatchResult {

        HideSnackBarFingerprint.result?.let {
            it.mutableMethod.apply {
                addInstructionsWithLabels(
                    0, """
                        invoke-static {}, $GENERAL->hideSnackBar()Z
                        move-result v0
                        if-eqz v0, :default
                        return-void
                        """, ExternalLabel("default", getInstruction(0))
                )
            }
        } ?: return HideSnackBarFingerprint.toErrorResult()

        /**
         * Add settings
         */
        SettingsPatch.addPreference(
            arrayOf(
                "PREFERENCE: GENERAL_SETTINGS",
                "SETTINGS: HIDE_SNACK_BAR"
            )
        )

        SettingsPatch.updatePatchStatus("hide-snack-bar")

        return PatchResultSuccess()
    }
}
