package com.papi.nova;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import com.papi.nova.binding.input.KeyboardTranslator;
import com.papi.nova.nvstream.input.KeyboardPacket;
import com.papi.nova.preferences.PreferenceConfiguration;
import com.papi.nova.utils.KeyMapper;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Modifier;

public class KotlinMenuInputMigrationTest {
    @Test
    public void menuInputClassesAreKotlinSources() {
        String[] names = {
                "GameMenu",
                "binding/input/KeyboardTranslator",
                "utils/KeyMapper"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void menuInputClassesKeepJavaCompatibleApis() throws Exception {
        assertTrue(Game.GameMenuCallbacks.class.isAssignableFrom(GameMenu.class));
        assertEquals(25L, GameMenu.KEY_UP_DELAY);
        assertEquals("specialPrefs", GameMenu.PREF_NAME);
        assertEquals("special_key", GameMenu.KEY_NAME);
        GameMenu.class.getConstructor(Game.class);
        GameMenu.class.getConstructor(Game.class, android.content.Context.class);
        GameMenu.class.getMethod("showMenu", com.papi.nova.binding.input.GameInputDevice.class);
        GameMenu.class.getMethod("hideMenu");
        GameMenu.class.getMethod("isMenuOpen");
        GameMenu.MenuOption.class.getConstructor(String.class, boolean.class, Runnable.class);
        GameMenu.MenuOption.class.getConstructor(String.class, Runnable.class);

        assertEquals(27, KeyboardTranslator.VK_ESCAPE);
        assertEquals(122, KeyboardTranslator.VK_F11);
        assertEquals(160, KeyboardTranslator.VK_LSHIFT);
        assertEquals(162, KeyboardTranslator.VK_LCONTROL);
        assertEquals(164, KeyboardTranslator.VK_LMENU);
        KeyboardTranslator.class.getConstructor(PreferenceConfiguration.class);
        KeyboardTranslator.class.getMethod("getModifier", short.class);
        KeyboardTranslator.class.getMethod("translate", int.class, int.class, int.class);
        KeyboardTranslator.class.getMethod("hasNormalizedMapping", int.class, int.class);

        assertEquals(1, KeyMapper.KEY_ESC);
        assertEquals(87, KeyMapper.KEY_F11);
        assertEquals(0x1B, KeyMapper.VK_ESCAPE);
        assertEquals(0x7A, KeyMapper.VK_F11);
        assertTrue(Modifier.isStatic(KeyMapper.class.getDeclaredField("VK_ESCAPE").getModifiers()));
        KeyMapper.class.getMethod("getWindowsKeyCode", int.class);
        KeyMapper.class.getMethod("setKeyMapping", int.class, int.class);
    }

    @Test
    public void keyboardTranslatorKeepsModifierAndKeyMappings() {
        assertEquals(KeyboardPacket.MODIFIER_SHIFT, KeyboardTranslator.getModifier((short) KeyboardTranslator.VK_LSHIFT));
        assertEquals(KeyboardPacket.MODIFIER_CTRL, KeyboardTranslator.getModifier((short) KeyboardTranslator.VK_LCONTROL));
        assertEquals(KeyboardPacket.MODIFIER_ALT, KeyboardTranslator.getModifier((short) KeyboardTranslator.VK_LMENU));
        assertEquals(KeyboardPacket.MODIFIER_META, KeyboardTranslator.getModifier((short) KeyboardTranslator.VK_LWIN));
        assertEquals(0, KeyboardTranslator.getModifier((short) KeyboardTranslator.VK_ESCAPE));

        KeyboardTranslator translator = new KeyboardTranslator(new PreferenceConfiguration());
        assertEquals((short) 0x801B, translator.translate(KeyEvent.KEYCODE_ESCAPE, 0, -1));
        assertEquals((short) 0x8041, translator.translate(KeyEvent.KEYCODE_A, 0, -1));
        assertEquals((short) 0x807A, translator.translate(KeyEvent.KEYCODE_F11, 0, -1));
        assertEquals((short) 0x801B, translator.translate(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_ESC, -1));
    }

    @Test
    public void keyMapperKeepsMutableLinuxToWindowsMapping() {
        int original = KeyMapper.getWindowsKeyCode(KeyMapper.KEY_ESC);
        try {
            KeyMapper.setKeyMapping(KeyMapper.KEY_ESC, KeyMapper.VK_F24);
            assertEquals(KeyMapper.VK_F24, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_ESC));
            assertEquals(-1, KeyMapper.getWindowsKeyCode(-1));
            assertEquals(-1, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_CNT));
        } finally {
            KeyMapper.setKeyMapping(KeyMapper.KEY_ESC, original);
        }
    }
}
