package com.hisa.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bolt11AmountParserTest {
    @Test
    fun `parses BOLT-11 amounts into millisats`() {
        assertEquals(1_000_000L, Bolt11AmountParser.amountMilliSats("lnbc10u1example"))
        assertEquals(100L, Bolt11AmountParser.amountMilliSats("lntb1n1example"))
        assertEquals(1L, Bolt11AmountParser.amountMilliSats("lnbc10p1example"))
    }

    @Test
    fun `rejects amountless and fractional-millisat invoices`() {
        assertNull(Bolt11AmountParser.amountMilliSats("lnbc1example"))
        assertNull(Bolt11AmountParser.amountMilliSats("lnbc1p1example"))
        assertNull(Bolt11AmountParser.amountMilliSats("not-a-bolt11-invoice"))
    }
}
