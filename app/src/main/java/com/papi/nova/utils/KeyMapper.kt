package com.papi.nova.utils

object KeyMapper {
    /* Linux Key Codes
     * From https://github.com/torvalds/linux/blob/master/include/uapi/linux/input-event-codes.h
    */
    const val KEY_RESERVED = 0
    const val KEY_ESC = 1
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11
    const val KEY_MINUS = 12
    const val KEY_EQUAL = 13
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_Q = 16
    const val KEY_W = 17
    const val KEY_E = 18
    const val KEY_R = 19
    const val KEY_T = 20
    const val KEY_Y = 21
    const val KEY_U = 22
    const val KEY_I = 23
    const val KEY_O = 24
    const val KEY_P = 25
    const val KEY_LEFTBRACE = 26
    const val KEY_RIGHTBRACE = 27
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32
    const val KEY_F = 33
    const val KEY_G = 34
    const val KEY_H = 35
    const val KEY_J = 36
    const val KEY_K = 37
    const val KEY_L = 38
    const val KEY_SEMICOLON = 39
    const val KEY_APOSTROPHE = 40
    const val KEY_GRAVE = 41
    const val KEY_LEFTSHIFT = 42
    const val KEY_BACKSLASH = 43
    const val KEY_Z = 44
    const val KEY_X = 45
    const val KEY_C = 46
    const val KEY_V = 47
    const val KEY_B = 48
    const val KEY_N = 49
    const val KEY_M = 50
    const val KEY_COMMA = 51
    const val KEY_DOT = 52
    const val KEY_SLASH = 53
    const val KEY_RIGHTSHIFT = 54
    const val KEY_KPASTERISK = 55
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_CAPSLOCK = 58
    const val KEY_F1 = 59
    const val KEY_F2 = 60
    const val KEY_F3 = 61
    const val KEY_F4 = 62
    const val KEY_F5 = 63
    const val KEY_F6 = 64
    const val KEY_F7 = 65
    const val KEY_F8 = 66
    const val KEY_F9 = 67
    const val KEY_F10 = 68
    const val KEY_NUMLOCK = 69
    const val KEY_SCROLLLOCK = 70
    const val KEY_KP7 = 71
    const val KEY_KP8 = 72
    const val KEY_KP9 = 73
    const val KEY_KPMINUS = 74
    const val KEY_KP4 = 75
    const val KEY_KP5 = 76
    const val KEY_KP6 = 77
    const val KEY_KPPLUS = 78
    const val KEY_KP1 = 79
    const val KEY_KP2 = 80
    const val KEY_KP3 = 81
    const val KEY_KP0 = 82
    const val KEY_KPDOT = 83

    const val KEY_ZENKAKUHANKAKU = 85
    const val KEY_102ND = 86
    const val KEY_F11 = 87
    const val KEY_F12 = 88
    const val KEY_RO = 89
    const val KEY_KATAKANA = 90
    const val KEY_HIRAGANA = 91
    const val KEY_HENKAN = 92
    const val KEY_KATAKANAHIRAGANA = 93
    const val KEY_MUHENKAN = 94
    const val KEY_KPJPCOMMA = 95
    const val KEY_KPENTER = 96
    const val KEY_RIGHTCTRL = 97
    const val KEY_KPSLASH = 98
    const val KEY_SYSRQ = 99
    const val KEY_RIGHTALT = 100
    const val KEY_LINEFEED = 101
    const val KEY_HOME = 102
    const val KEY_UP = 103
    const val KEY_PAGEUP = 104
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_END = 107
    const val KEY_DOWN = 108
    const val KEY_PAGEDOWN = 109
    const val KEY_INSERT = 110
    const val KEY_DELETE = 111
    const val KEY_MACRO = 112
    const val KEY_MUTE = 113
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115
    const val KEY_POWER = 116 	/* SC System Power Down */
    const val KEY_KPEQUAL = 117
    const val KEY_KPPLUSMINUS = 118
    const val KEY_PAUSE = 119
    const val KEY_SCALE = 120 	/* AL Compiz Scale (Expose) */

    const val KEY_KPCOMMA = 121
    const val KEY_HANGEUL = 122
    const val KEY_HANGUEL = KEY_HANGEUL
    const val KEY_HANJA = 123
    const val KEY_YEN = 124
    const val KEY_LEFTMETA = 125
    const val KEY_RIGHTMETA = 126
    const val KEY_COMPOSE = 127

    const val KEY_STOP = 128 	/* AC Stop */
    const val KEY_AGAIN = 129
    const val KEY_PROPS = 130 	/* AC Properties */
    const val KEY_UNDO = 131 	/* AC Undo */
    const val KEY_FRONT = 132
    const val KEY_COPY = 133 	/* AC Copy */
    const val KEY_OPEN = 134 	/* AC Open */
    const val KEY_PASTE = 135 	/* AC Paste */
    const val KEY_FIND = 136 	/* AC Search */
    const val KEY_CUT = 137 	/* AC Cut */
    const val KEY_HELP = 138 	/* AL Integrated Help Center */
    const val KEY_MENU = 139 	/* Menu (show menu) */
    const val KEY_CALC = 140 	/* AL Calculator */
    const val KEY_SETUP = 141
    const val KEY_SLEEP = 142 	/* SC System Sleep */
    const val KEY_WAKEUP = 143 	/* System Wake Up */
    const val KEY_FILE = 144 	/* AL Local Machine Browser */
    const val KEY_SENDFILE = 145
    const val KEY_DELETEFILE = 146
    const val KEY_XFER = 147
    const val KEY_PROG1 = 148
    const val KEY_PROG2 = 149
    const val KEY_WWW = 150 	/* AL Internet Browser */
    const val KEY_MSDOS = 151
    const val KEY_COFFEE = 152 	/* AL Terminal Lock/Screensaver */
    const val KEY_SCREENLOCK = KEY_COFFEE
    const val KEY_ROTATE_DISPLAY = 153 	/* Display orientation for e.g. tablets */
    const val KEY_DIRECTION = KEY_ROTATE_DISPLAY
    const val KEY_CYCLEWINDOWS = 154
    const val KEY_MAIL = 155
    const val KEY_BOOKMARKS = 156 	/* AC Bookmarks */
    const val KEY_COMPUTER = 157
    const val KEY_BACK = 158 	/* AC Back */
    const val KEY_FORWARD = 159 	/* AC Forward */
    const val KEY_CLOSECD = 160
    const val KEY_EJECTCD = 161
    const val KEY_EJECTCLOSECD = 162
    const val KEY_NEXTSONG = 163
    const val KEY_PLAYPAUSE = 164
    const val KEY_PREVIOUSSONG = 165
    const val KEY_STOPCD = 166
    const val KEY_RECORD = 167
    const val KEY_REWIND = 168
    const val KEY_PHONE = 169 	/* Media Select Telephone */
    const val KEY_ISO = 170
    const val KEY_CONFIG = 171 	/* AL Consumer Control Configuration */
    const val KEY_HOMEPAGE = 172 	/* AC Home */
    const val KEY_REFRESH = 173 	/* AC Refresh */
    const val KEY_EXIT = 174 	/* AC Exit */
    const val KEY_MOVE = 175
    const val KEY_EDIT = 176
    const val KEY_SCROLLUP = 177
    const val KEY_SCROLLDOWN = 178
    const val KEY_KPLEFTPAREN = 179
    const val KEY_KPRIGHTPAREN = 180
    const val KEY_NEW = 181 	/* AC New */
    const val KEY_REDO = 182 	/* AC Redo/Repeat */

    const val KEY_F13 = 183
    const val KEY_F14 = 184
    const val KEY_F15 = 185
    const val KEY_F16 = 186
    const val KEY_F17 = 187
    const val KEY_F18 = 188
    const val KEY_F19 = 189
    const val KEY_F20 = 190
    const val KEY_F21 = 191
    const val KEY_F22 = 192
    const val KEY_F23 = 193
    const val KEY_F24 = 194

    const val KEY_PLAYCD = 200
    const val KEY_PAUSECD = 201
    const val KEY_PROG3 = 202
    const val KEY_PROG4 = 203
    const val KEY_ALL_APPLICATIONS = 204 	/* AC Desktop Show All Applications */
    const val KEY_DASHBOARD = KEY_ALL_APPLICATIONS
    const val KEY_SUSPEND = 205
    const val KEY_CLOSE = 206 	/* AC Close */
    const val KEY_PLAY = 207
    const val KEY_FASTFORWARD = 208
    const val KEY_BASSBOOST = 209
    const val KEY_PRINT = 210 	/* AC Print */
    const val KEY_HP = 211
    const val KEY_CAMERA = 212
    const val KEY_SOUND = 213
    const val KEY_QUESTION = 214
    const val KEY_EMAIL = 215
    const val KEY_CHAT = 216
    const val KEY_SEARCH = 217
    const val KEY_CONNECT = 218
    const val KEY_FINANCE = 219 	/* AL Checkbook/Finance */
    const val KEY_SPORT = 220
    const val KEY_SHOP = 221
    const val KEY_ALTERASE = 222
    const val KEY_CANCEL = 223 	/* AC Cancel */
    const val KEY_BRIGHTNESSDOWN = 224
    const val KEY_BRIGHTNESSUP = 225
    const val KEY_MEDIA = 226

    const val KEY_SWITCHVIDEOMODE = 227	/* Cycle between available video
					   outputs (Monitor/LCD/TV-out/etc) */
    const val KEY_KBDILLUMTOGGLE = 228
    const val KEY_KBDILLUMDOWN = 229
    const val KEY_KBDILLUMUP = 230

    const val KEY_SEND = 231 	/* AC Send */
    const val KEY_REPLY = 232 	/* AC Reply */
    const val KEY_FORWARDMAIL = 233 	/* AC Forward Msg */
    const val KEY_SAVE = 234 	/* AC Save */
    const val KEY_DOCUMENTS = 235

    const val KEY_BATTERY = 236

    const val KEY_BLUETOOTH = 237
    const val KEY_WLAN = 238
    const val KEY_UWB = 239

    const val KEY_UNKNOWN = 240

    const val KEY_VIDEO_NEXT = 241	/* drive next video source */
    const val KEY_VIDEO_PREV = 242	/* drive previous video source */
    const val KEY_BRIGHTNESS_CYCLE = 243	/* brightness up, after max is min */
    const val KEY_BRIGHTNESS_AUTO = 244	/* Set Auto Brightness: manual
					  brightness control is off,
					  rely on ambient */
    const val KEY_BRIGHTNESS_ZERO = KEY_BRIGHTNESS_AUTO
    const val KEY_DISPLAY_OFF = 245 	/* display device to off state */

    const val KEY_WWAN = 246 	/* Wireless WAN (LTE, UMTS, GSM, etc.) */
    const val KEY_WIMAX = KEY_WWAN
    const val KEY_RFKILL = 247 	/* Key that controls all radios */

    const val KEY_MICMUTE = 248 	/* Mute / unmute the microphone */

/* Code 255 is reserved for special needs of AT keyboard driver */

    const val BTN_MISC = 0x100
    const val BTN_0 = 0x100
    const val BTN_1 = 0x101
    const val BTN_2 = 0x102
    const val BTN_3 = 0x103
    const val BTN_4 = 0x104
    const val BTN_5 = 0x105
    const val BTN_6 = 0x106
    const val BTN_7 = 0x107
    const val BTN_8 = 0x108
    const val BTN_9 = 0x109

    const val BTN_MOUSE = 0x110
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114
    const val BTN_FORWARD = 0x115
    const val BTN_BACK = 0x116
    const val BTN_TASK = 0x117

    const val BTN_JOYSTICK = 0x120
    const val BTN_TRIGGER = 0x120
    const val BTN_THUMB = 0x121
    const val BTN_THUMB2 = 0x122
    const val BTN_TOP = 0x123
    const val BTN_TOP2 = 0x124
    const val BTN_PINKIE = 0x125
    const val BTN_BASE = 0x126
    const val BTN_BASE2 = 0x127
    const val BTN_BASE3 = 0x128
    const val BTN_BASE4 = 0x129
    const val BTN_BASE5 = 0x12a
    const val BTN_BASE6 = 0x12b
    const val BTN_DEAD = 0x12f

    const val BTN_GAMEPAD = 0x130
    const val BTN_SOUTH = 0x130
    const val BTN_A = BTN_SOUTH
    const val BTN_EAST = 0x131
    const val BTN_B = BTN_EAST
    const val BTN_C = 0x132
    const val BTN_NORTH = 0x133
    const val BTN_X = BTN_NORTH
    const val BTN_WEST = 0x134
    const val BTN_Y = BTN_WEST
    const val BTN_Z = 0x135
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e

    const val BTN_DIGI = 0x140
    const val BTN_TOOL_PEN = 0x140
    const val BTN_TOOL_RUBBER = 0x141
    const val BTN_TOOL_BRUSH = 0x142
    const val BTN_TOOL_PENCIL = 0x143
    const val BTN_TOOL_AIRBRUSH = 0x144
    const val BTN_TOOL_FINGER = 0x145
    const val BTN_TOOL_MOUSE = 0x146
    const val BTN_TOOL_LENS = 0x147
    const val BTN_TOOL_QUINTTAP = 0x148 	/* Five fingers on trackpad */
    const val BTN_STYLUS3 = 0x149
    const val BTN_TOUCH = 0x14a
    const val BTN_STYLUS = 0x14b
    const val BTN_STYLUS2 = 0x14c
    const val BTN_TOOL_DOUBLETAP = 0x14d
    const val BTN_TOOL_TRIPLETAP = 0x14e
    const val BTN_TOOL_QUADTAP = 0x14f 	/* Four fingers on trackpad */

    const val BTN_WHEEL = 0x150
    const val BTN_GEAR_DOWN = 0x150
    const val BTN_GEAR_UP = 0x151

    const val KEY_OK = 0x160
    const val KEY_SELECT = 0x161
    const val KEY_GOTO = 0x162
    const val KEY_CLEAR = 0x163
    const val KEY_POWER2 = 0x164
    const val KEY_OPTION = 0x165
    const val KEY_INFO = 0x166 	/* AL OEM Features/Tips/Tutorial */
    const val KEY_TIME = 0x167
    const val KEY_VENDOR = 0x168
    const val KEY_ARCHIVE = 0x169
    const val KEY_PROGRAM = 0x16a 	/* Media Select Program Guide */
    const val KEY_CHANNEL = 0x16b
    const val KEY_FAVORITES = 0x16c
    const val KEY_EPG = 0x16d
    const val KEY_PVR = 0x16e 	/* Media Select Home */
    const val KEY_MHP = 0x16f
    const val KEY_LANGUAGE = 0x170
    const val KEY_TITLE = 0x171
    const val KEY_SUBTITLE = 0x172
    const val KEY_ANGLE = 0x173
    const val KEY_FULL_SCREEN = 0x174 	/* AC View Toggle */
    const val KEY_ZOOM = KEY_FULL_SCREEN
    const val KEY_MODE = 0x175
    const val KEY_KEYBOARD = 0x176
    const val KEY_ASPECT_RATIO = 0x177 	/* HUTRR37: Aspect */
    const val KEY_SCREEN = KEY_ASPECT_RATIO
    const val KEY_PC = 0x178 	/* Media Select Computer */
    const val KEY_TV = 0x179 	/* Media Select TV */
    const val KEY_TV2 = 0x17a 	/* Media Select Cable */
    const val KEY_VCR = 0x17b 	/* Media Select VCR */
    const val KEY_VCR2 = 0x17c 	/* VCR Plus */
    const val KEY_SAT = 0x17d 	/* Media Select Satellite */
    const val KEY_SAT2 = 0x17e
    const val KEY_CD = 0x17f 	/* Media Select CD */
    const val KEY_TAPE = 0x180 	/* Media Select Tape */
    const val KEY_RADIO = 0x181
    const val KEY_TUNER = 0x182 	/* Media Select Tuner */
    const val KEY_PLAYER = 0x183
    const val KEY_TEXT = 0x184
    const val KEY_DVD = 0x185 	/* Media Select DVD */
    const val KEY_AUX = 0x186
    const val KEY_MP3 = 0x187
    const val KEY_AUDIO = 0x188 	/* AL Audio Browser */
    const val KEY_VIDEO = 0x189 	/* AL Movie Browser */
    const val KEY_DIRECTORY = 0x18a
    const val KEY_LIST = 0x18b
    const val KEY_MEMO = 0x18c 	/* Media Select Messages */
    const val KEY_CALENDAR = 0x18d
    const val KEY_RED = 0x18e
    const val KEY_GREEN = 0x18f
    const val KEY_YELLOW = 0x190
    const val KEY_BLUE = 0x191
    const val KEY_CHANNELUP = 0x192 	/* Channel Increment */
    const val KEY_CHANNELDOWN = 0x193 	/* Channel Decrement */
    const val KEY_FIRST = 0x194
    const val KEY_LAST = 0x195 	/* Recall Last */
    const val KEY_AB = 0x196
    const val KEY_NEXT = 0x197
    const val KEY_RESTART = 0x198
    const val KEY_SLOW = 0x199
    const val KEY_SHUFFLE = 0x19a
    const val KEY_BREAK = 0x19b
    const val KEY_PREVIOUS = 0x19c
    const val KEY_DIGITS = 0x19d
    const val KEY_TEEN = 0x19e
    const val KEY_TWEN = 0x19f
    const val KEY_VIDEOPHONE = 0x1a0 	/* Media Select Video Phone */
    const val KEY_GAMES = 0x1a1 	/* Media Select Games */
    const val KEY_ZOOMIN = 0x1a2 	/* AC Zoom In */
    const val KEY_ZOOMOUT = 0x1a3 	/* AC Zoom Out */
    const val KEY_ZOOMRESET = 0x1a4 	/* AC Zoom */
    const val KEY_WORDPROCESSOR = 0x1a5 	/* AL Word Processor */
    const val KEY_EDITOR = 0x1a6 	/* AL Text Editor */
    const val KEY_SPREADSHEET = 0x1a7 	/* AL Spreadsheet */
    const val KEY_GRAPHICSEDITOR = 0x1a8 	/* AL Graphics Editor */
    const val KEY_PRESENTATION = 0x1a9 	/* AL Presentation App */
    const val KEY_DATABASE = 0x1aa 	/* AL Database App */
    const val KEY_NEWS = 0x1ab 	/* AL Newsreader */
    const val KEY_VOICEMAIL = 0x1ac 	/* AL Voicemail */
    const val KEY_ADDRESSBOOK = 0x1ad 	/* AL Contacts/Address Book */
    const val KEY_MESSENGER = 0x1ae 	/* AL Instant Messaging */
    const val KEY_DISPLAYTOGGLE = 0x1af 	/* Turn display (LCD) on and off */
    const val KEY_BRIGHTNESS_TOGGLE = KEY_DISPLAYTOGGLE
    const val KEY_SPELLCHECK = 0x1b0    /* AL Spell Check */
    const val KEY_LOGOFF = 0x1b1    /* AL Logoff */

    const val KEY_DOLLAR = 0x1b2
    const val KEY_EURO = 0x1b3

    const val KEY_FRAMEBACK = 0x1b4 	/* Consumer - transport controls */
    const val KEY_FRAMEFORWARD = 0x1b5
    const val KEY_CONTEXT_MENU = 0x1b6 	/* GenDesc - system context menu */
    const val KEY_MEDIA_REPEAT = 0x1b7 	/* Consumer - transport control */
    const val KEY_10CHANNELSUP = 0x1b8 	/* 10 channels up (10+) */
    const val KEY_10CHANNELSDOWN = 0x1b9 	/* 10 channels down (10-) */
    const val KEY_IMAGES = 0x1ba 	/* AL Image Browser */
    const val KEY_NOTIFICATION_CENTER = 0x1bc 	/* Show/hide the notification center */
    const val KEY_PICKUP_PHONE = 0x1bd 	/* Answer incoming call */
    const val KEY_HANGUP_PHONE = 0x1be 	/* Decline incoming call */

    const val KEY_DEL_EOL = 0x1c0
    const val KEY_DEL_EOS = 0x1c1
    const val KEY_INS_LINE = 0x1c2
    const val KEY_DEL_LINE = 0x1c3

    const val KEY_FN = 0x1d0
    const val KEY_FN_ESC = 0x1d1
    const val KEY_FN_F1 = 0x1d2
    const val KEY_FN_F2 = 0x1d3
    const val KEY_FN_F3 = 0x1d4
    const val KEY_FN_F4 = 0x1d5
    const val KEY_FN_F5 = 0x1d6
    const val KEY_FN_F6 = 0x1d7
    const val KEY_FN_F7 = 0x1d8
    const val KEY_FN_F8 = 0x1d9
    const val KEY_FN_F9 = 0x1da
    const val KEY_FN_F10 = 0x1db
    const val KEY_FN_F11 = 0x1dc
    const val KEY_FN_F12 = 0x1dd
    const val KEY_FN_1 = 0x1de
    const val KEY_FN_2 = 0x1df
    const val KEY_FN_D = 0x1e0
    const val KEY_FN_E = 0x1e1
    const val KEY_FN_F = 0x1e2
    const val KEY_FN_S = 0x1e3
    const val KEY_FN_B = 0x1e4
    const val KEY_FN_RIGHT_SHIFT = 0x1e5

    const val KEY_BRL_DOT1 = 0x1f1
    const val KEY_BRL_DOT2 = 0x1f2
    const val KEY_BRL_DOT3 = 0x1f3
    const val KEY_BRL_DOT4 = 0x1f4
    const val KEY_BRL_DOT5 = 0x1f5
    const val KEY_BRL_DOT6 = 0x1f6
    const val KEY_BRL_DOT7 = 0x1f7
    const val KEY_BRL_DOT8 = 0x1f8
    const val KEY_BRL_DOT9 = 0x1f9
    const val KEY_BRL_DOT10 = 0x1fa

    const val KEY_NUMERIC_0 = 0x200 	/* used by phones, remote controls, */
    const val KEY_NUMERIC_1 = 0x201 	/* and other keypads */
    const val KEY_NUMERIC_2 = 0x202
    const val KEY_NUMERIC_3 = 0x203
    const val KEY_NUMERIC_4 = 0x204
    const val KEY_NUMERIC_5 = 0x205
    const val KEY_NUMERIC_6 = 0x206
    const val KEY_NUMERIC_7 = 0x207
    const val KEY_NUMERIC_8 = 0x208
    const val KEY_NUMERIC_9 = 0x209
    const val KEY_NUMERIC_STAR = 0x20a
    const val KEY_NUMERIC_POUND = 0x20b
    const val KEY_NUMERIC_A = 0x20c 	/* Phone key A - HUT Telephony 0xb9 */
    const val KEY_NUMERIC_B = 0x20d
    const val KEY_NUMERIC_C = 0x20e
    const val KEY_NUMERIC_D = 0x20f

    const val KEY_CAMERA_FOCUS = 0x210
    const val KEY_WPS_BUTTON = 0x211 	/* WiFi Protected Setup key */

    const val KEY_TOUCHPAD_TOGGLE = 0x212 	/* Request switch touchpad on or off */
    const val KEY_TOUCHPAD_ON = 0x213
    const val KEY_TOUCHPAD_OFF = 0x214

    const val KEY_CAMERA_ZOOMIN = 0x215
    const val KEY_CAMERA_ZOOMOUT = 0x216
    const val KEY_CAMERA_UP = 0x217
    const val KEY_CAMERA_DOWN = 0x218
    const val KEY_CAMERA_LEFT = 0x219
    const val KEY_CAMERA_RIGHT = 0x21a

    const val KEY_ATTENDANT_ON = 0x21b
    const val KEY_ATTENDANT_OFF = 0x21c
    const val KEY_ATTENDANT_TOGGLE = 0x21d 	/* Attendant call on or off */
    const val KEY_LIGHTS_TOGGLE = 0x21e 	/* Reading light on or off */

    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221
    const val BTN_DPAD_LEFT = 0x222
    const val BTN_DPAD_RIGHT = 0x223

    const val KEY_ALS_TOGGLE = 0x230 	/* Ambient light sensor */
    const val KEY_ROTATE_LOCK_TOGGLE = 0x231 	/* Display rotation lock */
    const val KEY_REFRESH_RATE_TOGGLE = 0x232 	/* Display refresh rate toggle */

    const val KEY_BUTTONCONFIG = 0x240 	/* AL Button Configuration */
    const val KEY_TASKMANAGER = 0x241 	/* AL Task/Project Manager */
    const val KEY_JOURNAL = 0x242 	/* AL Log/Journal/Timecard */
    const val KEY_CONTROLPANEL = 0x243 	/* AL Control Panel */
    const val KEY_APPSELECT = 0x244 	/* AL Select Task/Application */
    const val KEY_SCREENSAVER = 0x245 	/* AL Screen Saver */
    const val KEY_VOICECOMMAND = 0x246 	/* Listening Voice Command */
    const val KEY_ASSISTANT = 0x247 	/* AL Context-aware desktop assistant */
    const val KEY_KBD_LAYOUT_NEXT = 0x248 	/* AC Next Keyboard Layout Select */
    const val KEY_EMOJI_PICKER = 0x249 	/* Show/hide emoji picker (HUTRR101) */
    const val KEY_DICTATE = 0x24a 	/* Start or Stop Voice Dictation Session (HUTRR99) */
    const val KEY_CAMERA_ACCESS_ENABLE = 0x24b 	/* Enables programmatic access to camera devices. (HUTRR72) */
    const val KEY_CAMERA_ACCESS_DISABLE = 0x24c 	/* Disables programmatic access to camera devices. (HUTRR72) */
    const val KEY_CAMERA_ACCESS_TOGGLE = 0x24d 	/* Toggles the current state of the camera access control. (HUTRR72) */
    const val KEY_ACCESSIBILITY = 0x24e 	/* Toggles the system bound accessibility UI/command (HUTRR116) */
    const val KEY_DO_NOT_DISTURB = 0x24f 	/* Toggles the system-wide "Do Not Disturb" control (HUTRR94)*/

    const val KEY_BRIGHTNESS_MIN = 0x250 	/* Set Brightness to Minimum */
    const val KEY_BRIGHTNESS_MAX = 0x251 	/* Set Brightness to Maximum */

    const val KEY_KBDINPUTASSIST_PREV = 0x260
    const val KEY_KBDINPUTASSIST_NEXT = 0x261
    const val KEY_KBDINPUTASSIST_PREVGROUP = 0x262
    const val KEY_KBDINPUTASSIST_NEXTGROUP = 0x263
    const val KEY_KBDINPUTASSIST_ACCEPT = 0x264
    const val KEY_KBDINPUTASSIST_CANCEL = 0x265

/* Diagonal movement keys */
    const val KEY_RIGHT_UP = 0x266
    const val KEY_RIGHT_DOWN = 0x267
    const val KEY_LEFT_UP = 0x268
    const val KEY_LEFT_DOWN = 0x269

    const val KEY_ROOT_MENU = 0x26a  /* Show Device's Root Menu */
/* Show Top Menu of the Media (e.g. DVD) */
    const val KEY_MEDIA_TOP_MENU = 0x26b
    const val KEY_NUMERIC_11 = 0x26c
    const val KEY_NUMERIC_12 = 0x26d
/*
 * Toggle Audio Description: refers to an audio service that helps blind and
 * visually impaired consumers understand the action in a program. Note: in
 * some countries this is referred to as "Video Description".
 */
    const val KEY_AUDIO_DESC = 0x26e
    const val KEY_3D_MODE = 0x26f
    const val KEY_NEXT_FAVORITE = 0x270
    const val KEY_STOP_RECORD = 0x271
    const val KEY_PAUSE_RECORD = 0x272
    const val KEY_VOD = 0x273  /* Video on Demand */
    const val KEY_UNMUTE = 0x274
    const val KEY_FASTREVERSE = 0x275
    const val KEY_SLOWREVERSE = 0x276
/*
 * Control a data application associated with the currently viewed channel,
 * e.g. teletext or data broadcast application (MHEG, MHP, HbbTV, etc.)
 */
    const val KEY_DATA = 0x277
    const val KEY_ONSCREEN_KEYBOARD = 0x278
/* Electronic privacy screen control */
    const val KEY_PRIVACY_SCREEN_TOGGLE = 0x279

/* Select an area of screen to be copied */
    const val KEY_SELECTIVE_SCREENSHOT = 0x27a

/* Move the focus to the next or previous user controllable element within a UI container */
    const val KEY_NEXT_ELEMENT = 0x27b
    const val KEY_PREVIOUS_ELEMENT = 0x27c

/* Toggle Autopilot engagement */
    const val KEY_AUTOPILOT_ENGAGE_TOGGLE = 0x27d

/* Shortcut Keys */
    const val KEY_MARK_WAYPOINT = 0x27e
    const val KEY_SOS = 0x27f
    const val KEY_NAV_CHART = 0x280
    const val KEY_FISHING_CHART = 0x281
    const val KEY_SINGLE_RANGE_RADAR = 0x282
    const val KEY_DUAL_RANGE_RADAR = 0x283
    const val KEY_RADAR_OVERLAY = 0x284
    const val KEY_TRADITIONAL_SONAR = 0x285
    const val KEY_CLEARVU_SONAR = 0x286
    const val KEY_SIDEVU_SONAR = 0x287
    const val KEY_NAV_INFO = 0x288
    const val KEY_BRIGHTNESS_MENU = 0x289

/*
 * Some keyboards have keys which do not have a defined meaning, these keys
 * are intended to be programmed / bound to macros by the user. For most
 * keyboards with these macro-keys the key-sequence to inject, or action to
 * take, is all handled by software on the host side. So from the kernel's
 * point of view these are just normal keys.
 *
 * The KEY_MACRO# codes below are intended for such keys, which may be labeled
 * e.g. G1-G18, or S1 - S30. The KEY_MACRO# codes MUST NOT be used for keys
 * where the marking on the key does indicate a defined meaning / purpose.
 *
 * The KEY_MACRO# codes MUST also NOT be used as fallback for when no existing
 * KEY_FOO define matches the marking / purpose. In this case a new KEY_FOO
 * define MUST be added.
 */
    const val KEY_MACRO1 = 0x290
    const val KEY_MACRO2 = 0x291
    const val KEY_MACRO3 = 0x292
    const val KEY_MACRO4 = 0x293
    const val KEY_MACRO5 = 0x294
    const val KEY_MACRO6 = 0x295
    const val KEY_MACRO7 = 0x296
    const val KEY_MACRO8 = 0x297
    const val KEY_MACRO9 = 0x298
    const val KEY_MACRO10 = 0x299
    const val KEY_MACRO11 = 0x29a
    const val KEY_MACRO12 = 0x29b
    const val KEY_MACRO13 = 0x29c
    const val KEY_MACRO14 = 0x29d
    const val KEY_MACRO15 = 0x29e
    const val KEY_MACRO16 = 0x29f
    const val KEY_MACRO17 = 0x2a0
    const val KEY_MACRO18 = 0x2a1
    const val KEY_MACRO19 = 0x2a2
    const val KEY_MACRO20 = 0x2a3
    const val KEY_MACRO21 = 0x2a4
    const val KEY_MACRO22 = 0x2a5
    const val KEY_MACRO23 = 0x2a6
    const val KEY_MACRO24 = 0x2a7
    const val KEY_MACRO25 = 0x2a8
    const val KEY_MACRO26 = 0x2a9
    const val KEY_MACRO27 = 0x2aa
    const val KEY_MACRO28 = 0x2ab
    const val KEY_MACRO29 = 0x2ac
    const val KEY_MACRO30 = 0x2ad

/*
 * Some keyboards with the macro-keys described above have some extra keys
 * for controlling the host-side software responsible for the macro handling:
 * -A macro recording start/stop key. Note that not all keyboards which emit
 *  KEY_MACRO_RECORD_START will also emit KEY_MACRO_RECORD_STOP if
 *  KEY_MACRO_RECORD_STOP is not advertised, then KEY_MACRO_RECORD_START
 *  should be interpreted as a recording start/stop toggle;
 * -Keys for switching between different macro (pre)sets, either a key for
 *  cycling through the configured presets or keys to directly select a preset.
 */
    const val KEY_MACRO_RECORD_START = 0x2b0
    const val KEY_MACRO_RECORD_STOP = 0x2b1
    const val KEY_MACRO_PRESET_CYCLE = 0x2b2
    const val KEY_MACRO_PRESET1 = 0x2b3
    const val KEY_MACRO_PRESET2 = 0x2b4
    const val KEY_MACRO_PRESET3 = 0x2b5

/*
 * Some keyboards have a buildin LCD panel where the contents are controlled
 * by the host. Often these have a number of keys directly below the LCD
 * intended for controlling a menu shown on the LCD. These keys often don't
 * have any labeling so we just name them KEY_KBD_LCD_MENU#
 */
    const val KEY_KBD_LCD_MENU1 = 0x2b8
    const val KEY_KBD_LCD_MENU2 = 0x2b9
    const val KEY_KBD_LCD_MENU3 = 0x2ba
    const val KEY_KBD_LCD_MENU4 = 0x2bb
    const val KEY_KBD_LCD_MENU5 = 0x2bc

    const val BTN_TRIGGER_HAPPY = 0x2c0
    const val BTN_TRIGGER_HAPPY1 = 0x2c0
    const val BTN_TRIGGER_HAPPY2 = 0x2c1
    const val BTN_TRIGGER_HAPPY3 = 0x2c2
    const val BTN_TRIGGER_HAPPY4 = 0x2c3
    const val BTN_TRIGGER_HAPPY5 = 0x2c4
    const val BTN_TRIGGER_HAPPY6 = 0x2c5
    const val BTN_TRIGGER_HAPPY7 = 0x2c6
    const val BTN_TRIGGER_HAPPY8 = 0x2c7
    const val BTN_TRIGGER_HAPPY9 = 0x2c8
    const val BTN_TRIGGER_HAPPY10 = 0x2c9
    const val BTN_TRIGGER_HAPPY11 = 0x2ca
    const val BTN_TRIGGER_HAPPY12 = 0x2cb
    const val BTN_TRIGGER_HAPPY13 = 0x2cc
    const val BTN_TRIGGER_HAPPY14 = 0x2cd
    const val BTN_TRIGGER_HAPPY15 = 0x2ce
    const val BTN_TRIGGER_HAPPY16 = 0x2cf
    const val BTN_TRIGGER_HAPPY17 = 0x2d0
    const val BTN_TRIGGER_HAPPY18 = 0x2d1
    const val BTN_TRIGGER_HAPPY19 = 0x2d2
    const val BTN_TRIGGER_HAPPY20 = 0x2d3
    const val BTN_TRIGGER_HAPPY21 = 0x2d4
    const val BTN_TRIGGER_HAPPY22 = 0x2d5
    const val BTN_TRIGGER_HAPPY23 = 0x2d6
    const val BTN_TRIGGER_HAPPY24 = 0x2d7
    const val BTN_TRIGGER_HAPPY25 = 0x2d8
    const val BTN_TRIGGER_HAPPY26 = 0x2d9
    const val BTN_TRIGGER_HAPPY27 = 0x2da
    const val BTN_TRIGGER_HAPPY28 = 0x2db
    const val BTN_TRIGGER_HAPPY29 = 0x2dc
    const val BTN_TRIGGER_HAPPY30 = 0x2dd
    const val BTN_TRIGGER_HAPPY31 = 0x2de
    const val BTN_TRIGGER_HAPPY32 = 0x2df
    const val BTN_TRIGGER_HAPPY33 = 0x2e0
    const val BTN_TRIGGER_HAPPY34 = 0x2e1
    const val BTN_TRIGGER_HAPPY35 = 0x2e2
    const val BTN_TRIGGER_HAPPY36 = 0x2e3
    const val BTN_TRIGGER_HAPPY37 = 0x2e4
    const val BTN_TRIGGER_HAPPY38 = 0x2e5
    const val BTN_TRIGGER_HAPPY39 = 0x2e6
    const val BTN_TRIGGER_HAPPY40 = 0x2e7

/* We avoid low common keys in module aliases so they don't get huge. */
    const val KEY_MIN_INTERESTING = KEY_MUTE
    const val KEY_MAX = 0x2ff
    const val KEY_CNT = (KEY_MAX+1)


/* Windows Virtual Key Codes
 * From https://learn.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
*/
    const val VK_LBUTTON = 0x01    // Left mouse button
    const val VK_RBUTTON = 0x02    // Right mouse button
    const val VK_CANCEL = 0x03    // Control-break processing
    const val VK_MBUTTON = 0x04    // Middle mouse button
    const val VK_XBUTTON1 = 0x05    // X1 mouse button
    const val VK_XBUTTON2 = 0x06    // X2 mouse button
    const val VK_BACK = 0x08    // BACKSPACE key
    const val VK_TAB = 0x09    // TAB key
    const val VK_CLEAR = 0x0C    // CLEAR key
    const val VK_RETURN = 0x0D    // ENTER key
    const val VK_SHIFT = 0x10    // SHIFT key
    const val VK_CONTROL = 0x11    // CTRL key
    const val VK_MENU = 0x12    // ALT key
    const val VK_PAUSE = 0x13    // PAUSE key
    const val VK_CAPITAL = 0x14    // CAPS LOCK key
    const val VK_KANA = 0x15    // IME Kana mode
    const val VK_HANGUL = 0x15    // IME Hangul mode
    const val VK_IME_ON = 0x16    // IME On
    const val VK_JUNJA = 0x17    // IME Junja mode
    const val VK_FINAL = 0x18    // IME mode
    const val VK_HANJA = 0x19    // IME Hanja mode
    const val VK_KANJI = 0x19    // IME Kanji mode
    const val VK_IME_OFF = 0x1A    // IME Off
    const val VK_ESCAPE = 0x1B    // ESC key
    const val VK_CONVERT = 0x1C    // IME convert
    const val VK_NONCONVERT = 0x1D    // IME nonconvert
    const val VK_ACCEPT = 0x1E    // IME accept
    const val VK_MODECHANGE = 0x1F    // IME mode change request
    const val VK_SPACE = 0x20    // SPACEBAR
    const val VK_PRIOR = 0x21    // PAGE UP key
    const val VK_NEXT = 0x22    // PAGE DOWN key
    const val VK_END = 0x23    // END key
    const val VK_HOME = 0x24    // HOME key
    const val VK_LEFT = 0x25    // LEFT ARROW key
    const val VK_UP = 0x26    // UP ARROW key
    const val VK_RIGHT = 0x27    // RIGHT ARROW key
    const val VK_DOWN = 0x28    // DOWN ARROW key
    const val VK_SELECT = 0x29    // SELECT key
    const val VK_PRINT = 0x2A    // PRINT key
    const val VK_EXECUTE = 0x2B    // EXECUTE key
    const val VK_SNAPSHOT = 0x2C    // PRINT SCREEN key
    const val VK_INSERT = 0x2D    // INS key
    const val VK_DELETE = 0x2E    // DEL key
    const val VK_HELP = 0x2F    // HELP key
    const val VK_0 = 0x30    // 0 key
    const val VK_1 = 0x31    // 1 key
    const val VK_2 = 0x32    // 2 key
    const val VK_3 = 0x33    // 3 key
    const val VK_4 = 0x34    // 4 key
    const val VK_5 = 0x35    // 5 key
    const val VK_6 = 0x36    // 6 key
    const val VK_7 = 0x37    // 7 key
    const val VK_8 = 0x38    // 8 key
    const val VK_9 = 0x39    // 9 key
    // 0x3A-40 Undefined
    const val VK_A = 0x41    // A key
    const val VK_B = 0x42    // B key
    const val VK_C = 0x43    // C key
    const val VK_D = 0x44    // D key
    const val VK_E = 0x45    // E key
    const val VK_F = 0x46    // F key
    const val VK_G = 0x47    // G key
    const val VK_H = 0x48    // H key
    const val VK_I = 0x49    // I key
    const val VK_J = 0x4A    // J key
    const val VK_K = 0x4B    // K key
    const val VK_L = 0x4C    // L key
    const val VK_M = 0x4D    // M key
    const val VK_N = 0x4E    // N key
    const val VK_O = 0x4F    // O key
    const val VK_P = 0x50    // P key
    const val VK_Q = 0x51    // Q key
    const val VK_R = 0x52    // R key
    const val VK_S = 0x53    // S key
    const val VK_T = 0x54    // T key
    const val VK_U = 0x55    // U key
    const val VK_V = 0x56    // V key
    const val VK_W = 0x57    // W key
    const val VK_X = 0x58    // X key
    const val VK_Y = 0x59    // Y key
    const val VK_Z = 0x5A    // Z key
    const val VK_LWIN = 0x5B    // Left Windows key
    const val VK_RWIN = 0x5C    // Right Windows key
    const val VK_APPS = 0x5D    // Applications key
    // 0x5E Reserved
    const val VK_SLEEP = 0x5F    // Computer Sleep key
    const val VK_NUMPAD0 = 0x60    // Numeric keypad 0 key
    const val VK_NUMPAD1 = 0x61    // Numeric keypad 1 key
    const val VK_NUMPAD2 = 0x62    // Numeric keypad 2 key
    const val VK_NUMPAD3 = 0x63    // Numeric keypad 3 key
    const val VK_NUMPAD4 = 0x64    // Numeric keypad 4 key
    const val VK_NUMPAD5 = 0x65    // Numeric keypad 5 key
    const val VK_NUMPAD6 = 0x66    // Numeric keypad 6 key
    const val VK_NUMPAD7 = 0x67    // Numeric keypad 7 key
    const val VK_NUMPAD8 = 0x68    // Numeric keypad 8 key
    const val VK_NUMPAD9 = 0x69    // Numeric keypad 9 key
    const val VK_MULTIPLY = 0x6A    // Multiply key
    const val VK_ADD = 0x6B    // Add key
    const val VK_SEPARATOR = 0x6C    // Separator key
    const val VK_SUBTRACT = 0x6D    // Subtract key
    const val VK_DECIMAL = 0x6E    // Decimal key
    const val VK_DIVIDE = 0x6F    // Divide key
    const val VK_F1 = 0x70    // F1 key
    const val VK_F2 = 0x71    // F2 key
    const val VK_F3 = 0x72    // F3 key
    const val VK_F4 = 0x73    // F4 key
    const val VK_F5 = 0x74    // F5 key
    const val VK_F6 = 0x75    // F6 key
    const val VK_F7 = 0x76    // F7 key
    const val VK_F8 = 0x77    // F8 key
    const val VK_F9 = 0x78    // F9 key
    const val VK_F10 = 0x79    // F10 key
    const val VK_F11 = 0x7A    // F11 key
    const val VK_F12 = 0x7B    // F12 key
    const val VK_F13 = 0x7C    // F13 key
    const val VK_F14 = 0x7D    // F14 key
    const val VK_F15 = 0x7E    // F15 key
    const val VK_F16 = 0x7F    // F16 key
    const val VK_F17 = 0x80    // F17 key
    const val VK_F18 = 0x81    // F18 key
    const val VK_F19 = 0x82    // F19 key
    const val VK_F20 = 0x83    // F20 key
    const val VK_F21 = 0x84    // F21 key
    const val VK_F22 = 0x85    // F22 key
    const val VK_F23 = 0x86    // F23 key
    const val VK_F24 = 0x87    // F24 key
    // 0x88-8F Reserved
    const val VK_NUMLOCK = 0x90    // NUM LOCK key
    const val VK_SCROLL = 0x91    // SCROLL LOCK key
    // 0x92-96 OEM specific
    // 0x97-9F Unassigned
    const val VK_LSHIFT = 0xA0    // Left SHIFT key
    const val VK_RSHIFT = 0xA1    // Right SHIFT key
    const val VK_LCONTROL = 0xA2    // Left CONTROL key
    const val VK_RCONTROL = 0xA3    // Right CONTROL key
    const val VK_LMENU = 0xA4    // Left ALT key
    const val VK_RMENU = 0xA5    // Right ALT key
    const val VK_BROWSER_BACK = 0xA6    // Browser Back key
    const val VK_BROWSER_FORWARD = 0xA7    // Browser Forward key
    const val VK_BROWSER_REFRESH = 0xA8    // Browser Refresh key
    const val VK_BROWSER_STOP = 0xA9    // Browser Stop key
    const val VK_BROWSER_SEARCH = 0xAA    // Browser Search key
    const val VK_BROWSER_FAVORITES = 0xAB    // Browser Favorites key
    const val VK_BROWSER_HOME = 0xAC    // Browser Start and Home key
    const val VK_VOLUME_MUTE = 0xAD    // Volume Mute key
    const val VK_VOLUME_DOWN = 0xAE    // Volume Down key
    const val VK_VOLUME_UP = 0xAF    // Volume Up key
    const val VK_MEDIA_NEXT_TRACK = 0xB0    // Next Track key
    const val VK_MEDIA_PREV_TRACK = 0xB1    // Previous Track key
    const val VK_MEDIA_STOP = 0xB2    // Stop Media key
    const val VK_MEDIA_PLAY_PAUSE = 0xB3    // Play/Pause Media key
    const val VK_LAUNCH_MAIL = 0xB4    // Start Mail key
    const val VK_LAUNCH_MEDIA_SELECT = 0xB5    // Select Media key
    const val VK_LAUNCH_APP1 = 0xB6    // Start Application 1 key
    const val VK_LAUNCH_APP2 = 0xB7    // Start Application 2 key
    // 0xB8-B9 Reserved
    const val VK_OEM_1 = 0xBA;    // Used for miscellaneous characters; it can vary by keyboard. For the US standard keyboard, the : key
    const val VK_OEM_PLUS = 0xBB    // For any country/region, the + key
    const val VK_OEM_COMMA = 0xBC    // For any country/region, the , key
    const val VK_OEM_MINUS = 0xBD    // For any country/region, the - key
    const val VK_OEM_PERIOD = 0xBE    // For any country/region, the . key
    const val VK_OEM_2 = 0xBF;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the /? key
    const val VK_OEM_3 = 0xC0;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the `~ key
    // 0xC1-DA Reserved
    const val VK_OEM_4 = 0xDB;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the [{ key
    const val VK_OEM_5 = 0xDC;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the \| key
    const val VK_OEM_6 = 0xDD;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the ]} key
    const val VK_OEM_7 = 0xDE;    // Used for miscellaneous characters it can vary by keyboard. For the US standard keyboard, the '" key
    const val VK_OEM_8 = 0xDF;    // Used for miscellaneous characters it can vary by keyboard.
    // 0xE0 Reserved
    // 0xE1 OEM specific
    const val VK_OEM_102 = 0xE2    // The <> keys on the US standard keyboard, or the \| key on the non-US 102-key keyboard
    // 0xE3-E4 OEM specific
    const val VK_PROCESSKEY = 0xE5    // IME PROCESS key
    // 0xE6 OEM specific
    const val VK_PACKET = 0xE7    // Used to pass Unicode characters as if they were keystrokes. The VK_PACKET key is the low word of a 32-bit Virtual Key value used for non-keyboard input methods. For more information, see Remark in KEYBDINPUT, SendInput, WM_KEYDOWN, and WM_KEYUP
    // 0xE8 Unassigned
    // 0xE9-F5 OEM specific
    const val VK_ATTN = 0xF6    // Attn key
    const val VK_CRSEL = 0xF7    // CrSel key
    const val VK_EXSEL = 0xF8    // ExSel key
    const val VK_EREOF = 0xF9    // Erase EOF key
    const val VK_PLAY = 0xFA    // Play key
    const val VK_ZOOM = 0xFB    // Zoom key
    const val VK_NONAME = 0xFC    // Reserved
    const val VK_PA1 = 0xFD    // PA1 key
    const val VK_OEM_CLEAR = 0xFE    // Clear key

    private val linuxToWindowsKeyMap = IntArray(KEY_CNT)

    init {
        // Initialize all mappings to -1 (invalid/unmapped)
        for (i in 0 until KEY_CNT) {
            linuxToWindowsKeyMap[i] = -1;
        }

        // Define mappings
        linuxToWindowsKeyMap[KEY_ESC] = VK_ESCAPE;
        linuxToWindowsKeyMap[KEY_1] = VK_1;
        linuxToWindowsKeyMap[KEY_2] = VK_2;
        linuxToWindowsKeyMap[KEY_3] = VK_3;
        linuxToWindowsKeyMap[KEY_4] = VK_4;
        linuxToWindowsKeyMap[KEY_5] = VK_5;
        linuxToWindowsKeyMap[KEY_6] = VK_6;
        linuxToWindowsKeyMap[KEY_7] = VK_7;
        linuxToWindowsKeyMap[KEY_8] = VK_8;
        linuxToWindowsKeyMap[KEY_9] = VK_9;
        linuxToWindowsKeyMap[KEY_0] = VK_0;
        linuxToWindowsKeyMap[KEY_MINUS] = VK_OEM_MINUS;
        linuxToWindowsKeyMap[KEY_EQUAL] = VK_OEM_PLUS;
        linuxToWindowsKeyMap[KEY_BACKSPACE] = VK_BACK;
        linuxToWindowsKeyMap[KEY_TAB] = VK_TAB;
        linuxToWindowsKeyMap[KEY_Q] = VK_Q;
        linuxToWindowsKeyMap[KEY_W] = VK_W;
        linuxToWindowsKeyMap[KEY_E] = VK_E;
        linuxToWindowsKeyMap[KEY_R] = VK_R;
        linuxToWindowsKeyMap[KEY_T] = VK_T;
        linuxToWindowsKeyMap[KEY_Y] = VK_Y;
        linuxToWindowsKeyMap[KEY_U] = VK_U;
        linuxToWindowsKeyMap[KEY_I] = VK_I;
        linuxToWindowsKeyMap[KEY_O] = VK_O;
        linuxToWindowsKeyMap[KEY_P] = VK_P;
        linuxToWindowsKeyMap[KEY_LEFTBRACE] = VK_OEM_4;
        linuxToWindowsKeyMap[KEY_RIGHTBRACE] = VK_OEM_6;
        linuxToWindowsKeyMap[KEY_ENTER] = VK_RETURN;
        linuxToWindowsKeyMap[KEY_A] = VK_A;
        linuxToWindowsKeyMap[KEY_S] = VK_S;
        linuxToWindowsKeyMap[KEY_D] = VK_D;
        linuxToWindowsKeyMap[KEY_F] = VK_F;
        linuxToWindowsKeyMap[KEY_G] = VK_G;
        linuxToWindowsKeyMap[KEY_H] = VK_H;
        linuxToWindowsKeyMap[KEY_J] = VK_J;
        linuxToWindowsKeyMap[KEY_K] = VK_K;
        linuxToWindowsKeyMap[KEY_L] = VK_L;
        linuxToWindowsKeyMap[KEY_SEMICOLON] = VK_OEM_1;
        linuxToWindowsKeyMap[KEY_APOSTROPHE] = VK_OEM_7;
        linuxToWindowsKeyMap[KEY_GRAVE] = VK_OEM_3;
        linuxToWindowsKeyMap[KEY_LEFTCTRL] = VK_LCONTROL;
        linuxToWindowsKeyMap[KEY_RIGHTCTRL] = VK_RCONTROL;
        linuxToWindowsKeyMap[KEY_LEFTSHIFT] = VK_LSHIFT;
        linuxToWindowsKeyMap[KEY_RIGHTSHIFT] = VK_RSHIFT;
        linuxToWindowsKeyMap[KEY_LEFTALT] = VK_LMENU;
        linuxToWindowsKeyMap[KEY_RIGHTALT] = VK_RMENU;
        linuxToWindowsKeyMap[KEY_LEFTMETA] = VK_LWIN;
        linuxToWindowsKeyMap[KEY_RIGHTMETA] = VK_RWIN;
        linuxToWindowsKeyMap[KEY_BACKSLASH] = VK_OEM_5;
        linuxToWindowsKeyMap[KEY_Z] = VK_Z;
        linuxToWindowsKeyMap[KEY_X] = VK_X;
        linuxToWindowsKeyMap[KEY_C] = VK_C;
        linuxToWindowsKeyMap[KEY_V] = VK_V;
        linuxToWindowsKeyMap[KEY_B] = VK_B;
        linuxToWindowsKeyMap[KEY_N] = VK_N;
        linuxToWindowsKeyMap[KEY_M] = VK_M;
        linuxToWindowsKeyMap[KEY_COMMA] = VK_OEM_COMMA;
        linuxToWindowsKeyMap[KEY_DOT] = VK_OEM_PERIOD;
        linuxToWindowsKeyMap[KEY_SLASH] = VK_OEM_2;
        linuxToWindowsKeyMap[KEY_KPASTERISK] = VK_MULTIPLY;
        linuxToWindowsKeyMap[KEY_SPACE] = VK_SPACE;
        linuxToWindowsKeyMap[KEY_CAPSLOCK] = VK_CAPITAL;
        linuxToWindowsKeyMap[KEY_F1] = VK_F1;
        linuxToWindowsKeyMap[KEY_F2] = VK_F2;
        linuxToWindowsKeyMap[KEY_F3] = VK_F3;
        linuxToWindowsKeyMap[KEY_F4] = VK_F4;
        linuxToWindowsKeyMap[KEY_F5] = VK_F5;
        linuxToWindowsKeyMap[KEY_F6] = VK_F6;
        linuxToWindowsKeyMap[KEY_F7] = VK_F7;
        linuxToWindowsKeyMap[KEY_F8] = VK_F8;
        linuxToWindowsKeyMap[KEY_F9] = VK_F9;
        linuxToWindowsKeyMap[KEY_F10] = VK_F10;
        linuxToWindowsKeyMap[KEY_F11] = VK_F11;
        linuxToWindowsKeyMap[KEY_F12] = VK_F12;
        linuxToWindowsKeyMap[KEY_F13] = VK_F13;
        linuxToWindowsKeyMap[KEY_F14] = VK_F14;
        linuxToWindowsKeyMap[KEY_F15] = VK_F15;
        linuxToWindowsKeyMap[KEY_F16] = VK_F16;
        linuxToWindowsKeyMap[KEY_F17] = VK_F17;
        linuxToWindowsKeyMap[KEY_F18] = VK_F18;
        linuxToWindowsKeyMap[KEY_F19] = VK_F19;
        linuxToWindowsKeyMap[KEY_F20] = VK_F20;
        linuxToWindowsKeyMap[KEY_F21] = VK_F21;
        linuxToWindowsKeyMap[KEY_F22] = VK_F22;
        linuxToWindowsKeyMap[KEY_F23] = VK_F23;
        linuxToWindowsKeyMap[KEY_F24] = VK_F24;
        linuxToWindowsKeyMap[KEY_SYSRQ] = VK_PRINT;
        linuxToWindowsKeyMap[KEY_SCROLLLOCK] = VK_SCROLL;
        linuxToWindowsKeyMap[KEY_PAUSE] = VK_PAUSE;
        linuxToWindowsKeyMap[KEY_INSERT] = VK_INSERT;
        linuxToWindowsKeyMap[KEY_HOME] = VK_HOME;
        linuxToWindowsKeyMap[KEY_PAGEUP] = VK_PRIOR;
        linuxToWindowsKeyMap[KEY_DELETE] = VK_DELETE;
        linuxToWindowsKeyMap[KEY_END] = VK_END;
        linuxToWindowsKeyMap[KEY_PAGEDOWN] = VK_NEXT;
        linuxToWindowsKeyMap[KEY_RIGHT] = VK_RIGHT;
        linuxToWindowsKeyMap[KEY_LEFT] = VK_LEFT;
        linuxToWindowsKeyMap[KEY_DOWN] = VK_DOWN;
        linuxToWindowsKeyMap[KEY_UP] = VK_UP;
        linuxToWindowsKeyMap[KEY_NUMLOCK] = VK_NUMLOCK;
        linuxToWindowsKeyMap[KEY_KP7] = VK_NUMPAD7;
        linuxToWindowsKeyMap[KEY_KP8] = VK_NUMPAD8;
        linuxToWindowsKeyMap[KEY_KP9] = VK_NUMPAD9;
        linuxToWindowsKeyMap[KEY_KPMINUS] = VK_SUBTRACT;
        linuxToWindowsKeyMap[KEY_KP4] = VK_NUMPAD4;
        linuxToWindowsKeyMap[KEY_KP5] = VK_NUMPAD5;
        linuxToWindowsKeyMap[KEY_KP6] = VK_NUMPAD6;
        linuxToWindowsKeyMap[KEY_KPPLUS] = VK_ADD;
        linuxToWindowsKeyMap[KEY_KP1] = VK_NUMPAD1;
        linuxToWindowsKeyMap[KEY_KP2] = VK_NUMPAD2;
        linuxToWindowsKeyMap[KEY_KP3] = VK_NUMPAD3;
        linuxToWindowsKeyMap[KEY_KP0] = VK_NUMPAD0;
        linuxToWindowsKeyMap[KEY_KPDOT] = VK_DECIMAL;
        linuxToWindowsKeyMap[KEY_102ND] = VK_OEM_102;
        linuxToWindowsKeyMap[KEY_COMPOSE] = VK_PROCESSKEY;
    }

    @JvmStatic
    fun getWindowsKeyCode(linuxKeyCode: Int): Int {
        if (linuxKeyCode >= 0 && linuxKeyCode < KEY_CNT) {
            return linuxToWindowsKeyMap[linuxKeyCode];
        }
        return -1; // Return -1 for out-of-range or unmapped keys
    }

    @JvmStatic
    fun setKeyMapping(linuxKeyCode: Int, windowsKeyCode: Int) {
        if (linuxKeyCode >= 0 && linuxKeyCode < KEY_CNT) {
            linuxToWindowsKeyMap[linuxKeyCode] = windowsKeyCode;
        }
    }
}
