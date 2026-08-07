package com.papi.nova.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.papi.nova.R

/**
 * The two faces the Polaris brand runs on, and the one rule about which goes where.
 *
 * Body, labels, values and titles stay on the platform face. Roboto is already on the
 * device, carries every weight, and is hinted by the system, so shipping a second body
 * font would cost APK size to gain nothing a user would notice.
 *
 * [NovaChromeFamily] is spent in exactly one place: the uppercase, letter-spaced chrome
 * — eyebrows, section heads, the launch status readout, the metadata line. That chrome
 * is meant to read as an instrument rather than as text, and it is the only part of the
 * window where a distinctive face earns its bytes.
 *
 * The style guide names Aldrich for this role. It was tried and dropped: Aldrich ships a
 * single weight and is a display face doing UI work, and at the 8-12sp these labels use,
 * under heavy tracking, it reads as generic rather than as branded.
 */
val NovaChromeFamily: FontFamily = FontFamily(
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold)
)

/**
 * @brief One text style for every uppercase chrome label, so tracking cannot drift.
 *
 * The call sites had accumulated five different letter-spacings for what is visually one
 * kind of label. Passing the size and taking the rest from here keeps them a family.
 *
 * @param fontSize the label size; these run small, 8-12sp
 * @param letterSpacing overridden only where a label has to fit a fixed width
 */
@Immutable
object NovaChromeType {
    const val DEFAULT_TRACKING_EM = 0.22f

    fun label(
        fontSize: TextUnit = 10.sp,
        letterSpacing: TextUnit = DEFAULT_TRACKING_EM.em,
    ): TextStyle = TextStyle(
        fontFamily = NovaChromeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        letterSpacing = letterSpacing,
    )
}
