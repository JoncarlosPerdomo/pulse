package com.kei.pulse.model

/** A special access only the system Settings screen can grant. */
enum class SpecialAccess { DRAW_OVERLAY, USAGE_ACCESS }

/** A feature the user switched on that needs special access first, in the order it's requested. */
enum class GatedFeature(val label: String, val requires: List<SpecialAccess>) {
    PER_APP("Per-app profiles", listOf(SpecialAccess.USAGE_ACCESS)),
    AUTO_TDP("AutoTDP", listOf(SpecialAccess.USAGE_ACCESS)),
    OVERLAY("Overlay", listOf(SpecialAccess.DRAW_OVERLAY, SpecialAccess.USAGE_ACCESS)),
    QUICK_ACCESS("Quick Access bar", listOf(SpecialAccess.DRAW_OVERLAY, SpecialAccess.USAGE_ACCESS)),
}

/** The user was sent to system Settings to grant [awaiting] so [feature] can turn on. */
data class PendingGrant(val feature: GatedFeature, val awaiting: SpecialAccess)

sealed interface GrantStep {
    data class Enable(val feature: GatedFeature) : GrantStep
    data class Request(val pending: PendingGrant) : GrantStep

    /** The user came back from Settings without granting what was asked; the feature stays off. */
    data class Declined(val pending: PendingGrant) : GrantStep
}

/**
 * Turning on a [GatedFeature]: request each missing [SpecialAccess] in turn (one Settings round-trip each),
 * then enable. Coming back without the awaited grant ends the flow instead of re-sending the user.
 */
object FeatureGrantFlow {
    fun start(feature: GatedFeature, granted: Set<SpecialAccess>): GrantStep =
        feature.requires.firstOrNull { it !in granted }
            ?.let { GrantStep.Request(PendingGrant(feature, it)) }
            ?: GrantStep.Enable(feature)

    fun resume(pending: PendingGrant, granted: Set<SpecialAccess>): GrantStep =
        if (pending.awaiting in granted) start(pending.feature, granted) else GrantStep.Declined(pending)
}
