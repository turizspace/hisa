package com.hisa.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionLifecycleStateMachineTest {
    @Test
    fun `duplicate start attempts are suppressed until the subscription becomes active`() {
        val stateMachine = SubscriptionLifecycleStateMachine()

        assertTrue(stateMachine.tryBeginStart())
        assertFalse(stateMachine.tryBeginStart())

        assertTrue(stateMachine.markActive())
        assertFalse(stateMachine.tryBeginStart())
    }

    @Test
    fun `stopping resets the machine so a new start can begin`() {
        val stateMachine = SubscriptionLifecycleStateMachine()

        assertTrue(stateMachine.tryBeginStart())
        stateMachine.markStopping()
        stateMachine.markIdle()

        assertTrue(stateMachine.tryBeginStart())
    }
}
