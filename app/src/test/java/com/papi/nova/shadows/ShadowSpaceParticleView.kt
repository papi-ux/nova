package com.papi.nova.shadows

import com.papi.nova.ui.SpaceParticleView
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowView

@Implements(SpaceParticleView::class)
class ShadowSpaceParticleView : ShadowView()
