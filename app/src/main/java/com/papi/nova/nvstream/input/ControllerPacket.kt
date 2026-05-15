package com.papi.nova.nvstream.input

object ControllerPacket {
    const val A_FLAG: Int = 0x1000
    const val B_FLAG: Int = 0x2000
    const val X_FLAG: Int = 0x4000
    const val Y_FLAG: Int = 0x8000
    const val UP_FLAG: Int = 0x0001
    const val DOWN_FLAG: Int = 0x0002
    const val LEFT_FLAG: Int = 0x0004
    const val RIGHT_FLAG: Int = 0x0008
    const val LB_FLAG: Int = 0x0100
    const val RB_FLAG: Int = 0x0200
    const val PLAY_FLAG: Int = 0x0010
    const val BACK_FLAG: Int = 0x0020
    const val LS_CLK_FLAG: Int = 0x0040
    const val RS_CLK_FLAG: Int = 0x0080
    const val SPECIAL_BUTTON_FLAG: Int = 0x0400

    // Extended buttons (Sunshine only)
    const val PADDLE1_FLAG: Int = 0x010000
    const val PADDLE2_FLAG: Int = 0x020000
    const val PADDLE3_FLAG: Int = 0x040000
    const val PADDLE4_FLAG: Int = 0x080000
    const val TOUCHPAD_FLAG: Int = 0x100000
    const val MISC_FLAG: Int = 0x200000
}
