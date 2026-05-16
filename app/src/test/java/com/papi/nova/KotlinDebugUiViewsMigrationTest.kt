package com.papi.nova

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.papi.nova.ui.AdapterFragment
import com.papi.nova.ui.ExternalControllerView
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinDebugUiViewsMigrationTest {
    @Test
    fun debugAndUiViewsAreKotlinSources() {
        val names = arrayOf(
            "DebugInfoActivity",
            "ui/AdapterFragment",
            "ui/ExternalControllerView"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedDebugAndUiViewsKeepJavaCompatibleApis() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(DebugInfoActivity::class.java))
        DebugInfoActivity::class.java.getConstructor()

        assertTrue(Fragment::class.java.isAssignableFrom(AdapterFragment::class.java))
        AdapterFragment::class.java.getConstructor()

        assertTrue(View::class.java.isAssignableFrom(ExternalControllerView::class.java))
        ExternalControllerView::class.java.getConstructor(Context::class.java)
        ExternalControllerView::class.java.getMethod(
            "setInputCallbacks",
            ExternalControllerView.InputCallbacks::class.java
        )
        ExternalControllerView::class.java.getMethod(
            "setCommitTextEnabled",
            Boolean::class.javaPrimitiveType!!
        )

        assertTrue(ExternalControllerView.InputCallbacks::class.java.isInterface)
        ExternalControllerView.InputCallbacks::class.java.getMethod("handleKeyUp", KeyEvent::class.java)
        ExternalControllerView.InputCallbacks::class.java.getMethod("handleKeyDown", KeyEvent::class.java)
        ExternalControllerView.InputCallbacks::class.java.getMethod("handleCommitText", CharSequence::class.java)
        ExternalControllerView.InputCallbacks::class.java.getMethod(
            "handleDeleteSurroundingText",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
    }
}
