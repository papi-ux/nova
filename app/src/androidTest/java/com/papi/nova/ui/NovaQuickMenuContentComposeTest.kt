package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NovaQuickMenuContentComposeTest {
    @Test
    fun quickMenuContentComposesInViewInteropHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = NovaQuickMenuUiState.preview(context).copy(advancedExpanded = true)
        val composeViewRef = AtomicReference<ComposeView>()
        val intent = Intent(context, NovaLibraryActivity::class.java).apply {
            putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "Test Server")
            putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 47984)
            putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 47989)
        }

        ActivityScenario.launch<NovaLibraryActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val composeView = ComposeView(activity)
                activity.setContentView(
                    composeView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                composeViewRef.set(composeView)
                composeView.setContent {
                    NovaComposeTheme {
                        NovaQuickMenuContent(
                            state = state,
                            callbacks = NovaQuickMenuCallbacks()
                        )
                    }
                }
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(composeViewRef.get().isAttachedToWindow)
            }
        }
    }
}
