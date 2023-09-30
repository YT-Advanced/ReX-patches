package app.revanced.patches.music.utils.sponsorblock.resource.patch

import app.revanced.patcher.data.ResourceContext
import app.revanced.patcher.patch.ResourcePatch
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patches.music.utils.settings.resource.patch.SettingsPatch
import app.revanced.patches.music.utils.sponsorblock.bytecode.patch.SponsorBlockBytecodePatch
import app.revanced.util.resources.MusicResourceHelper
import app.revanced.util.resources.MusicResourceHelper.hookPreference
import app.revanced.util.resources.ResourceUtils
import app.revanced.util.resources.ResourceUtils.copyResources

@Patch(
    name = "SponsorBlock",
    description = "Integrates SponsorBlock which allows skipping video segments such as sponsored content.",
    compatiblePackages = [
        CompatiblePackage(
            "com.google.android.apps.youtube.music",
            [
                "6.15.52",
                "6.20.51",
                "6.21.51"
            ]
        )
    ],
    dependencies = [
        SettingsPatch::class,
        SponsorBlockBytecodePatch::class
    ]
)
@Suppress("unused")
object SponsorBlockPatch : ResourcePatch {
    override fun execute(context: ResourceContext) {

        /**
         * Copy preference
         */
        arrayOf(
            ResourceUtils.ResourceGroup(
                "xml",
                "sponsorblock_prefs.xml"
            )
        ).forEach { resourceGroup ->
            context.copyResources("music/sponsorblock", resourceGroup)
        }

        /**
         * Hook SponsorBlock preference
         */
        context.hookPreference(
            "revanced_sponsorblock_settings",
            "com.google.android.apps.youtube.music.settings.fragment.AdvancedPrefsFragmentCompat"
        )

        val publicFile = context["res/values/public.xml"]

        publicFile.writeText(
            publicFile.readText()
                .replace(
                    "\"advanced_prefs_compat\"",
                    "\"sponsorblock_prefs\""
                )
        )

        context["res/xml/sponsorblock_prefs.xml"].writeText(
            context["res/xml/sponsorblock_prefs.xml"].readText()
                .replace(
                    "\"com.google.android.apps.youtube.music\"",
                    "\"" + MusicResourceHelper.targetPackage + "\""
                )
        )

    }
}