package com.papi.nova.shadows;

import com.papi.nova.ui.SpaceParticleView;

import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowView;

/**
 * Shadow for SpaceParticleView — extends ShadowView so Robolectric
 * can inflate it without the Canvas/particle system initialization.
 */
@Implements(SpaceParticleView.class)
public class ShadowSpaceParticleView extends ShadowView {
}
