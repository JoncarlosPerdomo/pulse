package com.kei.pulse.model

import com.kei.pulse.model.SpecialAccess.DRAW_OVERLAY
import com.kei.pulse.model.SpecialAccess.USAGE_ACCESS
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureGrantFlowTest {

    @Test
    fun enablesAtOnceWhenEverythingIsGranted() {
        assertEquals(
            GrantStep.Enable(GatedFeature.OVERLAY),
            FeatureGrantFlow.start(GatedFeature.OVERLAY, setOf(DRAW_OVERLAY, USAGE_ACCESS)),
        )
    }

    @Test
    fun overlayAsksForDrawOverlayThenUsageAccess() {
        val first = FeatureGrantFlow.start(GatedFeature.OVERLAY, emptySet())
        assertEquals(GrantStep.Request(PendingGrant(GatedFeature.OVERLAY, DRAW_OVERLAY)), first)

        val second = FeatureGrantFlow.resume((first as GrantStep.Request).pending, setOf(DRAW_OVERLAY))
        assertEquals(GrantStep.Request(PendingGrant(GatedFeature.OVERLAY, USAGE_ACCESS)), second)

        val done = FeatureGrantFlow.resume((second as GrantStep.Request).pending, setOf(DRAW_OVERLAY, USAGE_ACCESS))
        assertEquals(GrantStep.Enable(GatedFeature.OVERLAY), done)
    }

    @Test
    fun returningWithoutTheGrantEndsTheFlowInsteadOfAskingAgain() {
        val pending = PendingGrant(GatedFeature.QUICK_ACCESS, USAGE_ACCESS)
        assertEquals(GrantStep.Declined(pending), FeatureGrantFlow.resume(pending, setOf(DRAW_OVERLAY)))
    }
}
