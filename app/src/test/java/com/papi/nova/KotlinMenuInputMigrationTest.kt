package com.papi.nova

import android.view.KeyEvent
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.nvstream.input.KeyboardPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.KeyMapper
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinMenuInputMigrationTest {
    @Test
    fun menuInputClassesAreKotlinSources() {
        val names = arrayOf(
            "GameMenu",
            "binding/input/KeyboardTranslator",
            "utils/KeyMapper"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun menuInputClassesKeepJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!

        assertTrue(Game.GameMenuCallbacks::class.java.isAssignableFrom(GameMenu::class.java))
        assertEquals(25L, GameMenu.KEY_UP_DELAY)
        assertEquals("specialPrefs", GameMenu.PREF_NAME)
        assertEquals("special_key", GameMenu.KEY_NAME)
        GameMenu::class.java.getConstructor(Game::class.java)
        GameMenu::class.java.getConstructor(Game::class.java, android.content.Context::class.java)
        GameMenu::class.java.getMethod("showMenu", GameInputDevice::class.java)
        GameMenu::class.java.getMethod("hideMenu")
        GameMenu::class.java.getMethod("isMenuOpen")
        GameMenu.MenuOption::class.java.getConstructor(String::class.java, booleanType, Runnable::class.java)
        GameMenu.MenuOption::class.java.getConstructor(String::class.java, Runnable::class.java)

        assertEquals(27, KeyboardTranslator.VK_ESCAPE)
        assertEquals(122, KeyboardTranslator.VK_F11)
        assertEquals(160, KeyboardTranslator.VK_LSHIFT)
        assertEquals(162, KeyboardTranslator.VK_LCONTROL)
        assertEquals(164, KeyboardTranslator.VK_LMENU)
        KeyboardTranslator::class.java.getConstructor(PreferenceConfiguration::class.java)
        KeyboardTranslator::class.java.getMethod("getModifier", shortType)
        KeyboardTranslator::class.java.getMethod("translate", intType, intType, intType)
        KeyboardTranslator::class.java.getMethod("hasNormalizedMapping", intType, intType)

        assertEquals(1, KeyMapper.KEY_ESC)
        assertEquals(87, KeyMapper.KEY_F11)
        assertEquals(0x1B, KeyMapper.VK_ESCAPE)
        assertEquals(0x7A, KeyMapper.VK_F11)
        assertTrue(Modifier.isStatic(KeyMapper::class.java.getDeclaredField("VK_ESCAPE").modifiers))
        KeyMapper::class.java.getMethod("getWindowsKeyCode", intType)
        KeyMapper::class.java.getMethod("setKeyMapping", intType, intType)
    }

    @Test
    fun keyboardTranslatorKeepsModifierAndKeyMappings() {
        assertEquals(KeyboardPacket.MODIFIER_SHIFT, KeyboardTranslator.getModifier(KeyboardTranslator.VK_LSHIFT.toShort()))
        assertEquals(KeyboardPacket.MODIFIER_CTRL, KeyboardTranslator.getModifier(KeyboardTranslator.VK_LCONTROL.toShort()))
        assertEquals(KeyboardPacket.MODIFIER_ALT, KeyboardTranslator.getModifier(KeyboardTranslator.VK_LMENU.toShort()))
        assertEquals(KeyboardPacket.MODIFIER_META, KeyboardTranslator.getModifier(KeyboardTranslator.VK_LWIN.toShort()))
        assertEquals(0.toByte(), KeyboardTranslator.getModifier(KeyboardTranslator.VK_ESCAPE.toShort()))

        val translator = KeyboardTranslator(PreferenceConfiguration())
        assertEquals(0x801B.toShort(), translator.translate(KeyEvent.KEYCODE_ESCAPE, 0, -1))
        assertEquals(0x8041.toShort(), translator.translate(KeyEvent.KEYCODE_A, 0, -1))
        assertEquals(0x807A.toShort(), translator.translate(KeyEvent.KEYCODE_F11, 0, -1))
        assertEquals(0x801B.toShort(), translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_ESC, -1))
    }

    @Test
    fun keyMapperKeepsMutableLinuxToWindowsMapping() {
        val original = KeyMapper.getWindowsKeyCode(KeyMapper.KEY_ESC)
        try {
            KeyMapper.setKeyMapping(KeyMapper.KEY_ESC, KeyMapper.VK_F24)
            assertEquals(KeyMapper.VK_F24, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_ESC))
            assertEquals(-1, KeyMapper.getWindowsKeyCode(-1))
            assertEquals(-1, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_CNT))
        } finally {
            KeyMapper.setKeyMapping(KeyMapper.KEY_ESC, original)
        }
    }
}
