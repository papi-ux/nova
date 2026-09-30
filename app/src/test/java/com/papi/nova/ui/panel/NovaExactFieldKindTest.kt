package com.papi.nova.ui.panel

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Mbps field took 12.5 but asked for a keyboard with no decimal point (audit M9), so most
 * keyboards had no key to type it with.
 */
class NovaExactFieldKindTest {
    @Test
    fun aFieldInALargerUnitThanItsValueAsksForADecimalPoint() {
        assertEquals(NovaFieldKind.Decimal, novaExactFieldKind(negatives = false, divisor = 1000))
        assertEquals(KeyboardType.Decimal, NovaFieldKind.Decimal.keyboardType)
    }

    @Test
    fun wholeAndSignedFieldsKeepTheirKeyboards() {
        assertEquals(NovaFieldKind.Number, novaExactFieldKind(negatives = false, divisor = 1))
        assertEquals(NovaFieldKind.SignedNumber, novaExactFieldKind(negatives = true, divisor = 1))
        assertEquals(KeyboardType.Number, NovaFieldKind.Number.keyboardType)
    }
}
