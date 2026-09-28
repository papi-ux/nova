package com.papi.nova.ui.panel

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.NovaControllerHint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The content of a NovaPanelWindow: the panel frame with its stack, and the state pages above it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSurfacesLayerComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private var states by mutableStateOf(emptyList<NovaStatePage>())
    private val shoulders = mutableListOf<NovaShoulder>()
    private var rowActions = 0

    private fun setUp(
        hints: List<NovaControllerHint> = emptyList(),
        onShoulder: ((NovaShoulder) -> Unit)? = null,
    ): NovaTestKeys = rule.setPanelContent {
        NovaSurfacesLayer(
            panel = panel,
            states = states,
            scrim = NovaScrim.None,
            pageContent = { page ->
                NovaRow(title = "Row ${page.key}", onClick = { rowActions++ }, modifier = Modifier.novaInitialFocus())
            },
            onIdle = {},
            hints = hints,
            onShoulder = onShoulder,
        )
    }

    @Test
    fun aWindowPanelShowsTheOwnersHintsAndTakesTheShoulders() {
        panel.open(TestPage("options"))
        val keys = setUp(hints = listOf(NovaControllerHint("R1", "System")), onShoulder = { shoulders += it })

        rule.onNodeWithText("System").assertExists()
        keys.press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(NovaShoulder.Right), shoulders)
    }
}
