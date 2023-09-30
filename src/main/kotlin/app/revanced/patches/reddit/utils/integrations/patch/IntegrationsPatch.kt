package app.revanced.patches.reddit.utils.integrations.patch

import app.revanced.patcher.patch.annotations.RequiresIntegrations
import app.revanced.patches.reddit.utils.integrations.fingerprints.InitFingerprint
import app.revanced.patches.shared.patch.integrations.AbstractIntegrationsPatch

@Patch(requiresIntegrations = true)
object IntegrationsPatch : AbstractIntegrationsPatch(
    "Lapp/revanced/reddit/utils/ReVancedUtils;",
    setOf(InitFingerprint),
)