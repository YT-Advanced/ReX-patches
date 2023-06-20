package app.revanced.patches.music.layout.oldstyleminiplayer.patch

import app.revanced.extensions.toErrorResult
import app.revanced.patcher.annotation.Description
import app.revanced.patcher.annotation.Name
import app.revanced.patcher.annotation.Version
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.fingerprint.method.impl.MethodFingerprint.Companion.resolve
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.patch.PatchResultSuccess
import app.revanced.patcher.patch.annotations.DependsOn
import app.revanced.patcher.patch.annotations.Patch
import app.revanced.patches.music.utils.annotations.MusicCompatibility
import app.revanced.patches.music.layout.oldstyleminiplayer.fingerprints.NextButtonVisibilityFingerprint
import app.revanced.patches.music.layout.oldstyleminiplayer.fingerprints.SwipeToCloseFingerprint
import app.revanced.patches.music.utils.settings.resource.patch.SettingsPatch
import app.revanced.patches.music.utils.fingerprints.ColorMatchPlayerParentFingerprint
import app.revanced.util.enum.CategoryType
import app.revanced.util.integrations.Constants.MUSIC_LAYOUT
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction

@Patch
@Name("enable-old-style-miniplayer")
@Description("Return the miniplayers to old style. (for YT Music v5.55.53+)")
@DependsOn([SettingsPatch::class])
@MusicCompatibility
@Version("0.0.1")
class OldStyleMiniPlayerPatch : BytecodePatch(
    listOf(
        ColorMatchPlayerParentFingerprint,
        SwipeToCloseFingerprint
    )
) {
    override fun execute(context: BytecodeContext): PatchResult {

        ColorMatchPlayerParentFingerprint.result?.let { parentResult ->
            NextButtonVisibilityFingerprint.also {
                it.resolve(
                    context,
                    parentResult.classDef
                )
            }.result?.let {
                it.mutableMethod.apply {
                    val targetIndex = it.scanResult.patternScanResult!!.startIndex + 1
                    val targetRegister =
                        getInstruction<OneRegisterInstruction>(targetIndex).registerA

                    addInstructions(
                        targetIndex + 1, """
                            invoke-static {v$targetRegister}, $MUSIC_LAYOUT->enableOldStyleMiniPlayer(Z)Z
                            move-result v$targetRegister
                            """
                    )
                }
            } ?: return NextButtonVisibilityFingerprint.toErrorResult()
        } ?: return ColorMatchPlayerParentFingerprint.toErrorResult()

        SwipeToCloseFingerprint.result?.let {
            it.mutableMethod.apply {
                val insertIndex = implementation!!.instructions.size - 1
                val targetRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                addInstructions(
                    insertIndex, """
                        invoke-static {v$targetRegister}, $MUSIC_LAYOUT->enableOldStyleMiniPlayer(Z)Z
                        move-result v$targetRegister
                        """
                )
            }
        } ?: return SwipeToCloseFingerprint.toErrorResult()

        SettingsPatch.addMusicPreference(
            CategoryType.LAYOUT,
            "revanced_enable_old_style_mini_player",
            "false"
        )

        return PatchResultSuccess()
    }
}