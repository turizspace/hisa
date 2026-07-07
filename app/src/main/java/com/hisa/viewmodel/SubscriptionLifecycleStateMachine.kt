package com.hisa.viewmodel

class SubscriptionLifecycleStateMachine {
    private enum class State {
        IDLE,
        STARTING,
        ACTIVE,
        STOPPING
    }

    private var state: State = State.IDLE

    fun tryBeginStart(): Boolean {
        return if (state == State.IDLE) {
            state = State.STARTING
            true
        } else {
            false
        }
    }

    fun markActive(): Boolean {
        return if (state == State.STARTING) {
            state = State.ACTIVE
            true
        } else {
            false
        }
    }

    fun markStopping(): Boolean {
        return if (state == State.ACTIVE || state == State.STARTING) {
            state = State.STOPPING
            true
        } else {
            false
        }
    }

    fun markIdle(): Boolean {
        return if (state == State.STOPPING || state == State.ACTIVE || state == State.STARTING) {
            state = State.IDLE
            true
        } else {
            false
        }
    }
}
