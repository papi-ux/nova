package com.papi.nova.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.view.Display
import androidx.preference.PreferenceManager
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.utils.AndroidStreamDisplayTarget
import com.papi.nova.utils.DualScreenQuickMenuPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PreferenceConfiguration {
    enum class ScaleMode {
        FIT,
        FILL,
        STRETCH,
    }

    enum class FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    }

    enum class AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT,
    }

    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var bitrate = 0
    @JvmField var fps = 0f
    @JvmField var forceTightThresholds = false
    @JvmField var enableUltraLowLatency = false
    @JvmField var customResolution: String? = null
    @JvmField var customRefreshRate: String? = null
    @JvmField var meteredBitrate = 0
    @JvmField var videoFormat: FormatOption? = null
    @JvmField var framePacingWarpFactor = 0
    @JvmField var deadzonePercentage = 0
    @JvmField var oscOpacity = 0
    @JvmField var oscKeyboardOpacity = 0
    @JvmField var onscreenKeyboardHeight = 0
    @JvmField var onscreenKeyboardAutoFitDisabled = false
    @JvmField var onscreenKeyboardWidth = 0
    @JvmField var onscreenKeyboardAlignMode: String? = null
    @JvmField var enforceDisplayMode = false
    @JvmField var useVirtualDisplay = false
    @JvmField var enableSops = false
    @JvmField var playHostAudio = false
    @JvmField var disableWarnings = false
    @JvmField var fullScreen = false
    @JvmField var videoScaleMode: ScaleMode? = null
    @JvmField var language: String? = null
    @JvmField var smallIconMode = false
    @JvmField var multiController = false
    @JvmField var usbDriver = false
    @JvmField var flipFaceButtons = false
    @JvmField var onscreenController = false
    @JvmField var onscreenControllerLayoutPreset: String? = null
    @JvmField var hideOSCWhenHasGamepad = false
    @JvmField var enableBatteryReport = false
    @JvmField var forceQwerty = false
    @JvmField var backAsMeta = false
    @JvmField var ignoreSynthEvents = false
    @JvmField var backAsGuide = false
    @JvmField var smartClipboardSync = false
    @JvmField var smartClipboardSyncToast = false
    @JvmField var hideClipboardContent = false
    @JvmField var stickyModifierKey = false
    @JvmField var onlyL3R3 = false
    @JvmField var showGuideButton = false
    @JvmField var enableHdr = false
    @JvmField var enablePip = false
    @JvmField var enablePerfOverlay = false
    @JvmField var enablePerfLogging = false
    @JvmField var enablePerfOverlayLite = false
    @JvmField var enablePerfOverlayLiteDialog = false
    @JvmField var enablePerfOverlayBottom = false
    @JvmField var enableLatencyToast = false
    @JvmField var enableBackMenu = false
    @JvmField var enableFloatingButton = false
    @JvmField var showOverlayZoomToggleButton = false
    @JvmField var autoInvertVideoResolution = false
    @JvmField var resolutionScaleFactor = 0
    @JvmField var resumeWithoutConfirm = false
    @JvmField var keepStreamAlive = true
    @JvmField var disconnectResumeTimeoutSeconds = 300
    @JvmField var autoOrientation = false
    @JvmField var enableKeyboard = false
    @JvmField var enableJoyConFix = false
    @JvmField var enableNewAnalogStick = false
    @JvmField var enableFullExDisplay = false
    @JvmField var androidStreamDisplayTarget = AndroidStreamDisplayTarget.AUTO
    @JvmField var quickMenuDisplayPolicy = DualScreenQuickMenuPolicy.FOLLOW_INTERACTION
    @JvmField var companionScreenDimTimeoutSeconds = 10
    @JvmField var alignDisplayTopCenter = false
    @JvmField var touchSensitivityX = 0
    @JvmField var touchSensitivityY = 0
    @JvmField var touchSensitivityRotationAuto = false
    @JvmField var touchSensitivityGlobal = false
    @JvmField var enableTouchSensitivity = false
    @JvmField var touchPadSensitivity = 0
    @JvmField var touchPadYSensitity = 0
    @JvmField var enableMultiTouchScreen = false
    @JvmField var enableMouseLocalCursor = false
    @JvmField var enableMultiTouchGestures = false
    @JvmField var disableDefaultExtraKeys = false
    @JvmField var enableDeviceRumble = false
    @JvmField var enableCommitText = false
    @JvmField var enableKeyboardVibrate = false
    @JvmField var enableKeyboardSquare = false
    @JvmField var enableOnScreenStyleOfficial = false
    @JvmField var enableNewAnalogStickOpacity = 0
    @JvmField var trackpadSensitivityX = 0
    @JvmField var trackpadSensitivityY = 0
    @JvmField var trackpadDragDropVibration = false
    @JvmField var trackpadDragDropThreshold = 0
    @JvmField var trackpadSwapAxis = false
    @JvmField var bindAllUsb = false
    @JvmField var mouseEmulation = false
    @JvmField var analogStickForScrolling: AnalogStickForScrolling? = null
    @JvmField var mouseNavButtons = false
    @JvmField var rememberMouseMode = false
    @JvmField var unlockFps = false
    @JvmField var preferLowerDelays = false
    @JvmField var vibrateOsc = false
    @JvmField var vibrateFallbackToDevice = false
    @JvmField var vibrateFallbackToDeviceStrength = 0
    @JvmField var touchscreenTrackpad = false
    @JvmField var audioConfiguration: MoonBridge.AudioConfiguration? = null
    @JvmField var framePacing = 0
    @JvmField var absoluteMouseMode = false
    @JvmField var enableAudioFx = false
    @JvmField var reduceRefreshRate = false
    @JvmField var fullRange = false
    @JvmField var gamepadMotionSensors = false
    @JvmField var gamepadTouchpadAsMouse = false
    @JvmField var gamepadMotionSensorsFallbackToDevice = false
    @JvmField var forceMotionSensorsFallbackToDevice = false
    @JvmField var enableRumble = false
    @JvmField var preventPacketLoss = false
    @JvmField var rememberZoomPan = false
    @JvmField var zoomScale = 0f
    @JvmField var panOffsetX = 0f
    @JvmField var panOffsetY = 0f

    companion object {
        const val CUSTOM_BITRATE_PREF_STRING = "edit_diy_bitrate"
        const val CUSTOM_REFRESH_RATE_PREF_STRING = "custom_refresh_rate"
        const val CUSTOM_RESOLUTION_PREF_STRING = "edit_diy_w_h"

        private const val LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps"
        private const val LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround"
        private const val LEGACY_STRETCH_PREF_STRING = "checkbox_stretch_video"
        private const val LEGACY_ENFORCE_REFRESH_RATE_STRING = "checkbox_enforce_refresh_rate"

        const val RESOLUTION_PREF_STRING = "list_resolution"
        const val FPS_PREF_STRING = "list_fps"
        const val BITRATE_PREF_STRING = "seekbar_bitrate_kbps"
        private const val BITRATE_PREF_OLD_STRING = "seekbar_bitrate"
        private const val METERED_BITRATE_PREF_STRING = "seekbar_metered_bitrate_kbps"
        private const val ENABLE_ULTRA_LOW_LATENCY_PREF_STRING = "checkbox_ultra_low_latency"
        private const val ENFORCE_DISPLAY_MODE_PREF_STRING = "checkbox_enforce_display_mode"
        private const val USE_VIRTUAL_DISPLAY_PREF_STRING = "checkbox_use_virtual_display"
        const val ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING = "checkbox_enable_fullexdisplay"
        const val ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING = "android_stream_display_target"
        const val QUICK_MENU_DISPLAY_POLICY_PREF_STRING = "dual_screen_quick_menu_display_policy"
        const val COMPANION_SCREEN_DIM_TIMEOUT_PREF_STRING = "dual_screen_companion_dim_timeout_seconds"
        private const val AUTO_INVERT_VIDEO_RESOLUTION_PREF_STRING = "checkbox_auto_invert_video_resolution"
        private const val RESOLUTION_SCALE_FACTOR_PREF_STRING = "seekbar_resolution_scale_factor"
        private const val RESUME_WITHOUT_CONFIRM_PREF_STRING = "checkbox_resume_without_confirm"
        private const val KEEP_STREAM_ALIVE_PREF_STRING = "nova_keep_stream_alive"
        private const val DISCONNECT_RESUME_TIMEOUT_PREF_STRING = "nova_disconnect_resume_timeout_seconds"
        private const val VIDEO_SCALE_MODE_PREF_STRING = "list_video_scale_mode"
        private const val SOPS_PREF_STRING = "checkbox_enable_sops"
        private const val DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings"
        private const val HOST_AUDIO_PREF_STRING = "checkbox_host_audio"
        private const val DEADZONE_PREF_STRING = "seekbar_deadzone"
        private const val OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity"
        private const val LANGUAGE_PREF_STRING = "list_languages"
        private const val SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode"
        private const val MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller"
        const val AUDIO_CONFIG_PREF_STRING = "list_audio_config"
        private const val USB_DRIVER_PREF_SRING = "checkbox_usb_driver"
        private const val VIDEO_FORMAT_PREF_STRING = "video_format"
        private const val ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls"
        const val ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING = "list_onscreen_controls_layout_preset"
        private const val CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD = "checkbox_hide_osc_when_has_gamepad"
        private const val ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3"
        private const val SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button"
        private const val LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop"
        private const val ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr"
        private const val ENABLE_PIP_PREF_STRING = "checkbox_enable_pip"
        private const val ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay"
        private const val ENABLE_PERF_LOGGING = "checkbox_enable_perf_logging"
        private const val BIND_ALL_USB_STRING = "checkbox_usb_bind_all"
        private const val MOUSE_EMULATION_STRING = "checkbox_mouse_emulation"
        private const val REMEMBER_MOUSE_MODE_PREF_STRING = "checkbox_remember_mouse_mode"
        private const val ANALOG_SCROLLING_PREF_STRING = "analog_scrolling"
        private const val MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons"
        const val UNLOCK_FPS_STRING = "checkbox_unlock_fps"
        private const val VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc"
        private const val VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback"
        private const val VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength"
        private const val FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons"
        private const val LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast"
        private const val FRAME_PACING_PREF_STRING = "frame_pacing"
        private const val LOW_LATENCY_FRAME_BALANCE_PREF_STRING = "pref_low_latency_frame_balance"
        private const val ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode"
        private const val ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx"
        private const val REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate"
        private const val FULL_RANGE_PREF_STRING = "checkbox_full_range"
        private const val GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse"
        private const val GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors"
        private const val GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback"
        private const val FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING = "checkbox_force_device_motion"
        private const val FULL_SCREEN_PREF_STRING = "checkbox_full_screen"
        private const val ENABLE_RUMBLE_PREF_STRING = "checkbox_enable_rumble"
        private const val PREVENT_PACKET_LOSS_PREF_STRING = "checkbox_prevent_packet_loss"
        private const val LIST_ONSCREEN_KEYBOARD_ALIGN_MODE = "list_onscreen_keyboard_align_mode"
        private const val CHECKBOX_ENABLE_BATTERY_REPORT = "checkbox_gamepad_enable_battery_report"
        private const val CHECKBOX_FORCE_QWERTY = "checkbox_force_qwerty"
        private const val CHECKBOX_BACK_AS_META = "checkbox_back_as_meta"
        private const val CHECKBOX_IGNORE_SYNTH_EVENTS = "checkbox_ignore_synth_events"
        private const val CHECKBOX_BACK_AS_GUIDE = "checkbox_back_as_guide"
        private const val CHECKBOX_SMART_CLIPBOARD_SYNC = "checkbox_smart_clipboard_sync"
        private const val CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST = "checkbox_smart_clipboard_sync_toast"
        private const val CHECKBOX_HIDE_CLIPBOARD_CONTENT = "checkbox_hide_clipboard_content"
        private const val CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD =
            "checkbox_enable_sticky_modifier_key_virtual_keyboard"
        private const val CHECKBOX_ENABLE_QUIT_DIALOG = "checkbox_enable_quit_dialog"
        private const val CHECKBOX_ENABLE_FLOATING_BUTTON = "checkbox_enable_floating_button"
        private const val CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON = "checkbox_show_overlay_zoom_toggle_button"
        private const val CHECKBOX_AUTO_ORIENTATION = "checkbox_auto_orientation"
        private const val CHECKBOX_ENABLE_KEYBOARD = "checkbox_enable_keyboard"
        private const val CHECKBOX_ENABLE_KEYBOARD_VIBRATE = "checkbox_vibrate_keyboard"
        private const val CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW = "checkbox_enable_analog_stick_new"
        private const val SEEKBAR_TOUCH_SENSITIVITY = "seekbar_touch_sensitivity_opacity_x"
        private const val SEEKBAR_TRACKPAD_SENSITIVITY_X = "seekbar_trackpad_sensitivity_x"
        private const val SEEKBAR_TRACKPAD_SENSITIVITY_Y = "seekbar_trackpad_sensitivity_y"
        private const val CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION = "checkbox_trackpad_drag_drop_vibration"
        private const val SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD = "seekbar_trackpad_drag_drop_threshold"
        private const val CHECKBOX_TRACKPAD_SWAP_AXIS = "checkbox_trackpad_swap_axis"
        private const val CHECKBOX_ENABLE_COMMIT_TEXT = "checkbox_enable_commit_text"

        const val DEFAULT_RESOLUTION = "1920x1080"
        const val DEFAULT_FPS = "60"
        private const val LEGACY_BALANCED_RESOLUTION_MIGRATION =
            "__nova_migrated_balanced_default_resolution_20260519_v3"
        private const val LEGACY_DEFAULT_RESOLUTION = "1280x720"
        private const val LEGACY_BALANCED_BITRATE_KBPS = 15000
        private const val DEFAULT_ENABLE_ULTRA_LOW_LATENCY = false
        private const val DEFAULT_ENFORCE_DISPLAY_MODE = false
        private const val DEFAULT_USE_VIRTUAL_DISPLAY = false
        private const val DEFAULT_ANDROID_STREAM_DISPLAY_TARGET = AndroidStreamDisplayTarget.AUTO
        private const val DEFAULT_COMPANION_SCREEN_DIM_TIMEOUT_SECONDS = 10
        private const val DEFAULT_VIDEO_SCALE_MODE = "fit"
        private const val DEFAULT_AUTO_INVERT_VIDEO_RESOLUTION = true
        private const val DEFAULT_RESOLUTION_SCALE_FACTOR = 100
        private const val DEFAULT_RESUME_WITHOUT_CONFIRM = false
        private const val DEFAULT_KEEP_STREAM_ALIVE = true
        private const val DEFAULT_DISCONNECT_RESUME_TIMEOUT_SECONDS = 300
        private const val DEFAULT_SOPS = true
        private const val DEFAULT_DISABLE_TOASTS = false
        private const val DEFAULT_HOST_AUDIO = false
        private const val DEFAULT_DEADZONE = 5
        private const val DEFAULT_OPACITY = 90
        const val DEFAULT_LANGUAGE = "default"
        private const val DEFAULT_MULTI_CONTROLLER = true
        private const val DEFAULT_USB_DRIVER = true
        private const val DEFAULT_VIDEO_FORMAT = "auto"
        private const val DEFAULT_ONSCREEN_CONTROLLER = false
        const val ONSCREEN_CONTROLLER_LAYOUT_PRESET_COMPACT_HANDHELD = "compact_handheld"
        const val ONSCREEN_CONTROLLER_LAYOUT_PRESET_FULL_CONSOLE = "full_console"
        private const val DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD = true
        private const val ONLY_L3_R3_DEFAULT = false
        private const val SHOW_GUIDE_BUTTON_DEFAULT = true
        private const val DEFAULT_ENABLE_HDR = false
        private const val DEFAULT_ENABLE_PIP = false
        private const val DEFAULT_ENABLE_PERF_OVERLAY = false
        private const val DEFAULT_PERF_OVERLAY_BOTTOM = false
        private const val DEFAULT_ENABLE_PERF_LOGGING = false
        private const val DEFAULT_BIND_ALL_USB = false
        private const val DEFAULT_MOUSE_EMULATION = true
        private const val DEFAULT_REMEMBER_MOUSE_MODE = false
        private const val DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right"
        private const val DEFAULT_MOUSE_NAV_BUTTONS = false
        private const val DEFAULT_UNLOCK_FPS = false
        private const val DEFAULT_VIBRATE_OSC = true
        private const val DEFAULT_VIBRATE_FALLBACK = false
        private const val DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100
        private const val DEFAULT_FLIP_FACE_BUTTONS = false
        private const val DEFAULT_AUDIO_CONFIG = "2"
        private const val DEFAULT_LATENCY_TOAST = false
        private const val DEFAULT_FRAME_PACING = "latency"
        private const val DEFAULT_ABSOLUTE_MOUSE_MODE = false
        private const val DEFAULT_ENABLE_AUDIO_FX = false
        private const val DEFAULT_REDUCE_REFRESH_RATE = false
        private const val DEFAULT_FULL_RANGE = false
        private const val DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false
        private const val DEFAULT_GAMEPAD_MOTION_SENSORS = true
        private const val DEFAULT_GAMEPAD_MOTION_FALLBACK = false
        private const val DEFAULT_FORCE_MOTION_SENSORS_FALLBACK = false
        private const val DEFAULT_ENABLE_RUMBLE = true
        private const val DEFAULT_PREVENT_PACKET_LOSS = false
        private const val DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT = true
        private const val DEFAULT_FORCE_QWERTY = true
        private const val DEFAULT_SEND_META_ON_PHYSICAL_BACK = false
        private const val DEFAULT_IGNORE_SYNTH_EVENTS = false
        private const val DEFAULT_ENABLE_FLOATING_BUTTON = false
        private const val DEFAULT_BACK_AS_GUIDE = false
        private const val DEFAULT_SMART_CLIPBOARD_SYNC = false
        private const val DEFAULT_SMART_CLIPBOARD_SYNC_TOAST = true
        private const val DEFAULT_HIDE_CLIPBOARD_CONTENT = true
        private const val DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD = true
        private const val DEFAULT_TRACKPAD_SENSITIVITY_X = 100
        private const val DEFAULT_TRACKPAD_SENSITIVITY_Y = 100
        private const val DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION = false
        private const val DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD = 250
        private const val DEFAULT_TRACKPAD_SWAP_AXIS = false
        private const val DEFAULT_ENABLE_COMMIT_TEXT = false
        private const val DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE = "center"
        private const val DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON = false
        private const val DEFAULT_REMEMBER_ZOOM_PAN = false
        private const val DEFAULT_ZOOM_SCALE = 1.0f
        private const val DEFAULT_PAN_OFFSET = 0.0f
        private const val DEFAULT_FULL_SCREEN = true

        const val FRAME_PACING_MIN_LATENCY = 0
        const val FRAME_PACING_BALANCED = 1
        const val FRAME_PACING_CAP_FPS = 2
        const val FRAME_PACING_MAX_SMOOTHNESS = 3

        const val RES_360P = "640x360"
        const val RES_480P = "854x480"
        const val RES_720P = "1280x720"
        const val RES_1080P = "1920x1080"
        const val RES_1440P = "2560x1440"
        const val RES_4K = "3840x2160"
        const val RES_NATIVE = "Native"

        private const val CHECKBOX_REMEMBER_ZOOM_PAN = "checkbox_remember_zoom_pan"
        private const val NUMBER_ZOOM_SCALE = "number_zoom_scale"
        private const val NUMBER_PAN_OFFSET_X = "number_pan_offset_x"
        private const val NUMBER_PAN_OFFSET_Y = "number_pan_offset_y"

        @JvmStatic
        fun isNativeResolution(width: Int, height: Int): Boolean {
            return when {
                width == 640 && height == 360 -> false
                width == 854 && height == 480 -> false
                width == 1280 && height == 720 -> false
                width == 1920 && height == 1080 -> false
                width == 2560 && height == 1440 -> false
                width == 3840 && height == 2160 -> false
                else -> true
            }
        }

        @JvmStatic
        fun isSquarishScreen(width: Int, height: Int): Boolean {
            val longDim = max(width, height).toFloat()
            val shortDim = min(width, height).toFloat()
            return longDim / shortDim < 1.3f
        }

        @JvmStatic
        @Suppress("DEPRECATION")
        fun isSquarishScreen(display: Display): Boolean {
            val width: Int
            val height: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                width = display.mode.physicalWidth
                height = display.mode.physicalHeight
            } else {
                width = display.width
                height = display.height
            }
            return isSquarishScreen(width, height)
        }

        private fun convertFromLegacyResolutionString(resString: String): String {
            return when {
                resString.equals("360p", ignoreCase = true) -> RES_360P
                resString.equals("480p", ignoreCase = true) -> RES_480P
                resString.equals("720p", ignoreCase = true) -> RES_720P
                resString.equals("1080p", ignoreCase = true) -> RES_1080P
                resString.equals("1440p", ignoreCase = true) -> RES_1440P
                resString.equals("4K", ignoreCase = true) -> RES_4K
                else -> RES_720P
            }
        }

        private fun getWidthFromResolutionString(resString: String): Int {
            return resString.split("x")[0].toInt()
        }

        private fun getHeightFromResolutionString(resString: String): Int {
            return resString.split("x")[1].toInt()
        }

        private fun getResolutionString(width: Int, height: Int): String {
            return when (height) {
                360 -> RES_360P
                480 -> RES_480P
                1080 -> RES_1080P
                1440 -> RES_1440P
                2160 -> RES_4K
                else -> RES_720P
            }
        }

        @JvmStatic
        fun getDefaultBitrate(resString: String, fpsString: String): Int {
            val width = getWidthFromResolutionString(resString)
            val height = getHeightFromResolutionString(resString)
            val fps = fpsString.toFloat().roundToInt()
            val frameRateFactor = (if (fps <= 60) fps.toDouble() else sqrt(fps / 60.0) * 60.0) / 30.0
            val pixelVals = intArrayOf(
                640 * 360,
                854 * 480,
                1280 * 720,
                1920 * 1080,
                2560 * 1440,
                3840 * 2160,
                -1,
            )
            val factorVals = intArrayOf(1, 2, 5, 10, 20, 40, -1)
            val pixels = width * height
            var resolutionFactor = 0f
            var i = 0
            while (true) {
                if (pixels == pixelVals[i]) {
                    resolutionFactor = factorVals[i].toFloat()
                    break
                } else if (pixels < pixelVals[i]) {
                    resolutionFactor = if (i == 0) {
                        factorVals[i].toFloat()
                    } else {
                        ((pixels - pixelVals[i - 1]).toFloat() / (pixelVals[i] - pixelVals[i - 1])) *
                            (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1]
                    }
                    break
                } else if (pixelVals[i] == -1) {
                    resolutionFactor = factorVals[i - 1].toFloat()
                    break
                }
                i++
            }
            return (resolutionFactor * frameRateFactor).roundToInt() * 1000
        }

        @JvmStatic
        fun getDefaultSmallMode(context: Context): Boolean {
            val manager = context.packageManager
            if (manager != null) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                        return false
                    }
                }
            }
            return context.resources.configuration.smallestScreenWidthDp < 500
        }

        @JvmStatic
        fun getDefaultOnScreenControllerLayoutPreset(context: Context): String {
            return if (context.getSharedPreferences("OSC", Context.MODE_PRIVATE).all.isEmpty()) {
                ONSCREEN_CONTROLLER_LAYOUT_PRESET_COMPACT_HANDHELD
            } else {
                ONSCREEN_CONTROLLER_LAYOUT_PRESET_FULL_CONSOLE
            }
        }

        @JvmStatic
        fun getDefaultBitrate(context: Context): Int {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION,
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS,
            )
        }

        private fun getVideoFormatValue(context: Context): FormatOption {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT)) {
                "forceav1" -> FormatOption.FORCE_AV1
                "forceh265" -> FormatOption.FORCE_HEVC
                "neverh265" -> FormatOption.FORCE_H264
                else -> FormatOption.AUTO
            }
        }

        private fun getVideoScaleMode(context: Context): ScaleMode {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(VIDEO_SCALE_MODE_PREF_STRING, DEFAULT_VIDEO_SCALE_MODE)) {
                "fill" -> ScaleMode.FILL
                "stretch" -> ScaleMode.STRETCH
                else -> ScaleMode.FIT
            }
        }

        @JvmStatic
        fun getSelectedFramePacingName(context: Context): String {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING) ?: DEFAULT_FRAME_PACING
        }

        @JvmStatic
        fun getPreferLowerDelays(context: Context): Boolean {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return prefs.getBoolean(LOW_LATENCY_FRAME_BALANCE_PREF_STRING, false)
        }

        private fun getFramePacingValue(context: Context): Int {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
                val legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false)
                prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, if (legacyNeverDropFrames) "balanced" else "latency")
                    .apply()
            }

            return when (prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING)) {
                "balanced" -> FRAME_PACING_BALANCED
                "cap-fps" -> FRAME_PACING_CAP_FPS
                "smoothness" -> FRAME_PACING_MAX_SMOOTHNESS
                else -> FRAME_PACING_MIN_LATENCY
            }
        }

        private fun getAnalogStickForScrollingValue(context: Context): AnalogStickForScrolling {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING)) {
                "right" -> AnalogStickForScrolling.RIGHT
                "left" -> AnalogStickForScrolling.LEFT
                else -> AnalogStickForScrolling.NONE
            }
        }

        @JvmStatic
        fun resetStreamingSettings(context: Context) {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply()
        }

        @JvmStatic
        fun formatStreamingDisplayMode(width: Int, height: Int, fps: Float): String {
            return width.toString() + "x" + height + "x" + formatFpsValue(fps)
        }

        @JvmStatic
        fun formatCurrentStreamingDisplayMode(context: Context): String {
            val config = readPreferences(context)
            return formatStreamingDisplayMode(config.width, config.height, config.fps)
        }

        @JvmStatic
        fun applyPolarisStreamingProfile(context: Context, displayMode: String?, bitrateKbps: Int): Boolean {
            val mode = parseStreamingDisplayMode(displayMode)
            if (mode == null && bitrateKbps <= 0) {
                return false
            }

            val editor = ProfilesManager.getInstance()
                .getOverlayingSharedPreferences(context)
                .edit()
            if (mode != null) {
                editor.putString(RESOLUTION_PREF_STRING, mode.width.toString() + "x" + mode.height)
                editor.putString(FPS_PREF_STRING, formatFpsValue(mode.fps))
            }
            if (bitrateKbps > 0) {
                editor.putInt(BITRATE_PREF_STRING, bitrateKbps)
            }
            editor.apply()
            return true
        }

        private fun formatFpsValue(fps: Float): String {
            val rounded = fps.roundToInt()
            return if (abs(fps - rounded) < 0.01f) {
                rounded.toString()
            } else {
                String.format(Locale.US, "%.2f", fps)
            }
        }

        private fun parseStreamingDisplayMode(displayMode: String?): ParsedDisplayMode? {
            if (displayMode == null || displayMode.trim().isEmpty()) {
                return null
            }

            val normalized = displayMode.trim()
                .replace("@", "x")
                .replace("Hz", "")
                .replace("hz", "")
                .replace(" ", "")
            val parts = normalized.split("[xX]".toRegex()).toTypedArray()
            if (parts.size < 3) {
                return null
            }

            return try {
                val width = parts[0].toInt()
                val height = parts[1].toInt()
                val fps = parts[2].toFloat()
                if (width <= 0 || height <= 0 || fps <= 0) {
                    null
                } else {
                    ParsedDisplayMode(width, height, fps)
                }
            } catch (e: NumberFormatException) {
                null
            }
        }

        private data class ParsedDisplayMode(
            val width: Int,
            val height: Int,
            val fps: Float,
        )

        @JvmStatic
        fun isShieldAtvFirmwareWithBrokenHdr(): Boolean {
            return Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395")
        }

        @JvmStatic
        fun migrateLegacyBalancedResolutionDefault(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean(LEGACY_BALANCED_RESOLUTION_MIGRATION, false)) {
                return false
            }

            val preset = prefs.getString("nova_stream_preset", StreamPreset.BALANCED.key)
                ?: StreamPreset.BALANCED.key
            val legacyResFps = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null)
            val resolution = if (legacyResFps == "720p60") {
                LEGACY_DEFAULT_RESOLUTION
            } else {
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
            }
            val fps = if (legacyResFps == "720p60") {
                StreamPreset.BALANCED.fps
            } else {
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS
            }
            val bitrate = if (prefs.contains(BITRATE_PREF_STRING)) {
                prefs.getInt(BITRATE_PREF_STRING, StreamPreset.BALANCED.bitrateKbps)
            } else {
                prefs.getInt(BITRATE_PREF_OLD_STRING, LEGACY_BALANCED_BITRATE_KBPS / 1000) * 1000
            }
            val codec = prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT) ?: DEFAULT_VIDEO_FORMAT
            val isBalancedDefaultBitrate =
                bitrate == StreamPreset.BALANCED.bitrateKbps ||
                    bitrate == LEGACY_BALANCED_BITRATE_KBPS
            val shouldMigrate =
                preset == StreamPreset.BALANCED.key &&
                    resolution == LEGACY_DEFAULT_RESOLUTION &&
                    fps == StreamPreset.BALANCED.fps &&
                    isBalancedDefaultBitrate &&
                    codec == StreamPreset.BALANCED.codec

            val editor = prefs.edit()
                .putBoolean(LEGACY_BALANCED_RESOLUTION_MIGRATION, true)
            if (shouldMigrate) {
                editor.putString(RESOLUTION_PREF_STRING, StreamPreset.BALANCED.resolution)
                editor.putString(FPS_PREF_STRING, StreamPreset.BALANCED.fps)
                editor.remove(LEGACY_RES_FPS_PREF_STRING)
            }
            editor.apply()
            return shouldMigrate
        }

        @JvmStatic
        fun readPreferences(context: Context): PreferenceConfiguration {
            return readPreferences(context, null)
        }

        @JvmStatic
        fun readPreferences(context: Context, sharedPrefs: SharedPreferences?): PreferenceConfiguration {
            if (sharedPrefs == null) {
                migrateLegacyBalancedResolutionDefault(context)
            }

            val prefs = sharedPrefs ?: ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            val config = PreferenceConfiguration()

            if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
                if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                    prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply()
                }
            }

            val legacyResFps = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null)
            if (legacyResFps != null) {
                when (legacyResFps) {
                    "360p30" -> {
                        config.width = 640
                        config.height = 360
                        config.fps = 30f
                    }
                    "360p60" -> {
                        config.width = 640
                        config.height = 360
                        config.fps = 60f
                    }
                    "720p30" -> {
                        config.width = 1280
                        config.height = 720
                        config.fps = 30f
                    }
                    "720p60" -> {
                        config.width = 1280
                        config.height = 720
                        config.fps = 60f
                    }
                    "1080p30" -> {
                        config.width = 1920
                        config.height = 1080
                        config.fps = 30f
                    }
                    "1080p60" -> {
                        config.width = 1920
                        config.height = 1080
                        config.fps = 60f
                    }
                    "4K30" -> {
                        config.width = 3840
                        config.height = 2160
                        config.fps = 30f
                    }
                    "4K60" -> {
                        config.width = 3840
                        config.height = 2160
                        config.fps = 60f
                    }
                    else -> {
                        config.width = 1280
                        config.height = 720
                        config.fps = 60f
                    }
                }

                prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, config.fps.toString())
                    .apply()
            } else {
                var resStr = prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
                if (!resStr.contains("x")) {
                    resStr = convertFromLegacyResolutionString(resStr)
                    prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply()
                }

                config.width = getWidthFromResolutionString(resStr)
                config.height = getHeightFromResolutionString(resStr)
                config.fps = (prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS).toFloat()
            }

            if (prefs.contains(LEGACY_STRETCH_PREF_STRING)) {
                val stretch = prefs.getBoolean(LEGACY_STRETCH_PREF_STRING, false)
                prefs.edit()
                    .remove(LEGACY_STRETCH_PREF_STRING)
                    .putString(VIDEO_SCALE_MODE_PREF_STRING, if (stretch) "stretch" else "fit")
                    .apply()
            }

            if (prefs.contains(LEGACY_ENFORCE_REFRESH_RATE_STRING)) {
                val enforce = prefs.getBoolean(LEGACY_ENFORCE_REFRESH_RATE_STRING, false)
                prefs.edit()
                    .remove(LEGACY_ENFORCE_REFRESH_RATE_STRING)
                    .putBoolean(ENFORCE_DISPLAY_MODE_PREF_STRING, enforce)
                    .apply()
            }

            if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
                prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply()
            }

            if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
                prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply()
            }

            config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000)
            if (config.bitrate == 0) {
                config.bitrate = getDefaultBitrate(context)
            }

            config.meteredBitrate = prefs.getInt(METERED_BITRATE_PREF_STRING, 0)
            if (config.meteredBitrate == 0) {
                config.meteredBitrate = config.bitrate / 4
                prefs.edit().putInt(METERED_BITRATE_PREF_STRING, 0).apply()
            }

            config.audioConfiguration = when (prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG)) {
                "71" -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                "51" -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                else -> MoonBridge.AUDIO_CONFIGURATION_STEREO
            }

            config.videoScaleMode = getVideoScaleMode(context)
            config.videoFormat = getVideoFormatValue(context)
            config.framePacing = getFramePacingValue(context)
            config.preferLowerDelays = getPreferLowerDelays(context)

            when (prefs.getString(FRAME_PACING_PREF_STRING, "")) {
                "warp" -> config.framePacingWarpFactor = 2
                "warp2" -> config.framePacingWarpFactor = 4
            }

            config.analogStickForScrolling = getAnalogStickForScrollingValue(context)
            config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE)
            config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY)
            config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE)
            config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS)
            config.enforceDisplayMode = prefs.getBoolean(ENFORCE_DISPLAY_MODE_PREF_STRING, DEFAULT_ENFORCE_DISPLAY_MODE)
            config.useVirtualDisplay = prefs.getBoolean(USE_VIRTUAL_DISPLAY_PREF_STRING, DEFAULT_USE_VIRTUAL_DISPLAY)
            config.enableUltraLowLatency =
                prefs.getBoolean(ENABLE_ULTRA_LOW_LATENCY_PREF_STRING, DEFAULT_ENABLE_ULTRA_LOW_LATENCY)
            config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS)
            config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO)
            config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context))
            config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER)
            config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER)
            config.fullScreen = prefs.getBoolean(FULL_SCREEN_PREF_STRING, DEFAULT_FULL_SCREEN)

            when ((prefs.getString("mouse_mode_list", "0") ?: "0").toInt()) {
                0 -> {
                    config.enableMultiTouchScreen = true
                    config.touchscreenTrackpad = false
                }
                1, 5 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = false
                }
                2, 3 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = true
                }
                4 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = false
                }
            }

            config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, DEFAULT_ONSCREEN_CONTROLLER)
            if (!prefs.contains(ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING)) {
                prefs.edit()
                    .putString(
                        ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING,
                        getDefaultOnScreenControllerLayoutPreset(context),
                    )
                    .apply()
            }
            config.onscreenControllerLayoutPreset = prefs.getString(
                ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING,
                getDefaultOnScreenControllerLayoutPreset(context),
            )
            config.hideOSCWhenHasGamepad =
                prefs.getBoolean(CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD, DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD)
            config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT)
            config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT)
            config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) &&
                !isShieldAtvFirmwareWithBrokenHdr()
            config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP)
            config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY)
            config.enablePerfLogging = prefs.getBoolean(ENABLE_PERF_LOGGING, DEFAULT_ENABLE_PERF_LOGGING)
            config.enablePerfOverlayLite =
                prefs.getBoolean("checkbox_enable_perf_overlay_lite", DEFAULT_ENABLE_PERF_OVERLAY)
            config.enablePerfOverlayBottom =
                prefs.getBoolean("checkbox_enable_perf_overlay_bottom", DEFAULT_PERF_OVERLAY_BOTTOM)
            config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB)
            config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION)
            config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS)
            config.rememberMouseMode = prefs.getBoolean(REMEMBER_MOUSE_MODE_PREF_STRING, DEFAULT_REMEMBER_MOUSE_MODE)
            config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS)
            config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC)
            config.vibrateFallbackToDevice =
                prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK)
            config.vibrateFallbackToDeviceStrength =
                prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH)
            config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS)
            config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST)
            config.enableBackMenu = prefs.getBoolean(CHECKBOX_ENABLE_QUIT_DIALOG, true)
            config.enableFloatingButton =
                prefs.getBoolean(CHECKBOX_ENABLE_FLOATING_BUTTON, DEFAULT_ENABLE_FLOATING_BUTTON)
            config.showOverlayZoomToggleButton =
                prefs.getBoolean(CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON, DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON)
            config.autoOrientation = prefs.getBoolean(CHECKBOX_AUTO_ORIENTATION, false)
            config.autoInvertVideoResolution =
                prefs.getBoolean(AUTO_INVERT_VIDEO_RESOLUTION_PREF_STRING, DEFAULT_AUTO_INVERT_VIDEO_RESOLUTION)
            config.resolutionScaleFactor =
                prefs.getInt(RESOLUTION_SCALE_FACTOR_PREF_STRING, DEFAULT_RESOLUTION_SCALE_FACTOR)
            config.resumeWithoutConfirm =
                prefs.getBoolean(RESUME_WITHOUT_CONFIRM_PREF_STRING, DEFAULT_RESUME_WITHOUT_CONFIRM)
            config.keepStreamAlive = prefs.getBoolean(KEEP_STREAM_ALIVE_PREF_STRING, DEFAULT_KEEP_STREAM_ALIVE)
            config.disconnectResumeTimeoutSeconds = prefs.getString(
                DISCONNECT_RESUME_TIMEOUT_PREF_STRING,
                DEFAULT_DISCONNECT_RESUME_TIMEOUT_SECONDS.toString(),
            )?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_DISCONNECT_RESUME_TIMEOUT_SECONDS
            config.enableKeyboard = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD, false)
            config.enableKeyboardVibrate = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD_VIBRATE, false)
            config.enableJoyConFix = prefs.getBoolean("checkbox_joycon_fix", false)
            config.oscKeyboardOpacity = prefs.getInt("seekbar_keyboard_axi_opacity", DEFAULT_OPACITY)
            config.enableOnScreenStyleOfficial = prefs.getBoolean("checkbox_onscreen_style_official", false)
            config.enableNewAnalogStickOpacity = prefs.getInt("seekbar_osc_free_analog_stick_opacity", 20)
            config.onscreenKeyboardHeight = prefs.getInt("seekbar_onscreen_keyboard_height", 200)
            config.onscreenKeyboardAutoFitDisabled = prefs.getBoolean("onscreen_keyboard_autofit", false)
            config.onscreenKeyboardWidth = prefs.getInt("seekbar_onscreen_keyboard_width", 1000)
            config.onscreenKeyboardAlignMode =
                prefs.getString(LIST_ONSCREEN_KEYBOARD_ALIGN_MODE, DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE)
            config.enableNewAnalogStick = prefs.getBoolean(CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW, false)
            config.enableFullExDisplay = prefs.getBoolean(ENABLE_FULL_EXTERNAL_DISPLAY_PREF_STRING, false)
            config.androidStreamDisplayTarget = prefs.getString(
                ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING,
                DEFAULT_ANDROID_STREAM_DISPLAY_TARGET,
            ) ?: DEFAULT_ANDROID_STREAM_DISPLAY_TARGET
            config.quickMenuDisplayPolicy = DualScreenQuickMenuPolicy.normalize(
                prefs.getString(
                    QUICK_MENU_DISPLAY_POLICY_PREF_STRING,
                    DualScreenQuickMenuPolicy.FOLLOW_INTERACTION,
                )
            )
            config.companionScreenDimTimeoutSeconds = prefs.getString(
                COMPANION_SCREEN_DIM_TIMEOUT_PREF_STRING,
                DEFAULT_COMPANION_SCREEN_DIM_TIMEOUT_SECONDS.toString(),
            )?.toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_COMPANION_SCREEN_DIM_TIMEOUT_SECONDS
            config.alignDisplayTopCenter = prefs.getBoolean("checkbox_enable_view_top_center", false)
            config.touchSensitivityX = prefs.getInt(SEEKBAR_TOUCH_SENSITIVITY, 100)
            config.touchSensitivityY = prefs.getInt("seekbar_touch_sensitivity_opacity_y", 100)
            config.touchSensitivityRotationAuto =
                prefs.getBoolean("checkbox_enable_touch_sensitivity_rotation_auto", true)
            config.touchSensitivityGlobal = prefs.getBoolean("checkbox_enable_global_touch_sensitivity", false)
            config.enableTouchSensitivity = prefs.getBoolean("checkbox_enable_touch_sensitivity", false)
            config.enableMouseLocalCursor = prefs.getBoolean("checkbox_mouse_local_cursor", false)
            config.enableMultiTouchGestures = prefs.getBoolean("checkbox_multi_touch_gestures", false)
            config.enablePerfOverlayLiteDialog =
                prefs.getBoolean("checkbox_enable_perf_overlay_lite_dialog", false)
            config.disableDefaultExtraKeys =
                prefs.getBoolean("checkbox_enable_clear_default_special_button", false)
            config.enableDeviceRumble = prefs.getBoolean("checkbox_enable_device_rumble", false)
            config.enableCommitText = prefs.getBoolean(CHECKBOX_ENABLE_COMMIT_TEXT, DEFAULT_ENABLE_COMMIT_TEXT)
            config.enableKeyboardSquare = prefs.getBoolean("checkbox_enable_keyboard_square", false)
            config.touchPadSensitivity = prefs.getInt("seekbar_touchpad_sensitivity_opacity", 100)
            config.touchPadYSensitity = prefs.getInt("seekbar_touchpad_sensitivity_y_opacity", 100)
            config.trackpadSensitivityX =
                prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_X, DEFAULT_TRACKPAD_SENSITIVITY_X)
            config.trackpadSensitivityY =
                prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_Y, DEFAULT_TRACKPAD_SENSITIVITY_Y)
            config.trackpadDragDropVibration =
                prefs.getBoolean(CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION, DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION)
            config.trackpadDragDropThreshold =
                prefs.getInt(SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD, DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD)
            config.trackpadSwapAxis = prefs.getBoolean(CHECKBOX_TRACKPAD_SWAP_AXIS, DEFAULT_TRACKPAD_SWAP_AXIS)
            config.absoluteMouseMode =
                prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE)
            config.enableBatteryReport =
                prefs.getBoolean(CHECKBOX_ENABLE_BATTERY_REPORT, DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT)
            config.forceQwerty = prefs.getBoolean(CHECKBOX_FORCE_QWERTY, DEFAULT_FORCE_QWERTY)
            config.backAsMeta = prefs.getBoolean(CHECKBOX_BACK_AS_META, DEFAULT_SEND_META_ON_PHYSICAL_BACK)
            config.ignoreSynthEvents = prefs.getBoolean(CHECKBOX_IGNORE_SYNTH_EVENTS, DEFAULT_IGNORE_SYNTH_EVENTS)
            config.backAsGuide = prefs.getBoolean(CHECKBOX_BACK_AS_GUIDE, DEFAULT_BACK_AS_GUIDE)
            config.smartClipboardSync =
                prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC, DEFAULT_SMART_CLIPBOARD_SYNC)
            config.smartClipboardSyncToast =
                prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST, DEFAULT_SMART_CLIPBOARD_SYNC_TOAST)
            config.hideClipboardContent =
                prefs.getBoolean(CHECKBOX_HIDE_CLIPBOARD_CONTENT, DEFAULT_HIDE_CLIPBOARD_CONTENT)
            config.stickyModifierKey = prefs.getBoolean(
                CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD,
                DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD,
            )
            config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX)
            config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE)
            config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE)
            config.gamepadTouchpadAsMouse =
                prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE)
            config.gamepadMotionSensors =
                prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS)
            config.gamepadMotionSensorsFallbackToDevice =
                prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK)
            config.forceMotionSensorsFallbackToDevice =
                prefs.getBoolean(FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING, DEFAULT_FORCE_MOTION_SENSORS_FALLBACK)
            config.enableRumble = prefs.getBoolean(ENABLE_RUMBLE_PREF_STRING, DEFAULT_ENABLE_RUMBLE)
            config.preventPacketLoss = prefs.getBoolean(PREVENT_PACKET_LOSS_PREF_STRING, DEFAULT_PREVENT_PACKET_LOSS)
            config.customResolution = prefs.getString(CUSTOM_RESOLUTION_PREF_STRING, null)
            config.customRefreshRate = prefs.getString(CUSTOM_REFRESH_RATE_PREF_STRING, null)
            config.rememberZoomPan = prefs.getBoolean(CHECKBOX_REMEMBER_ZOOM_PAN, DEFAULT_REMEMBER_ZOOM_PAN)
            config.zoomScale = prefs.getFloat(NUMBER_ZOOM_SCALE, DEFAULT_ZOOM_SCALE)
            config.panOffsetX = prefs.getFloat(NUMBER_PAN_OFFSET_X, DEFAULT_PAN_OFFSET)
            config.panOffsetY = prefs.getFloat(NUMBER_PAN_OFFSET_Y, DEFAULT_PAN_OFFSET)

            return config
        }
    }
}
