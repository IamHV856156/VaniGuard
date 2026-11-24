
package com.vanigaurd.app

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallUiState(
    val call: Call? = null,
    val number: String = "Unknown caller",
    val state: Int = Call.STATE_DISCONNECTED,
    val muted: Boolean = false,
    val speaker: Boolean = false
)

object CallUiController {
    private val mutableState = MutableStateFlow(CallUiState())
    val state = mutableState.asStateFlow()

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var inCallService: InCallService? = null

    fun bindService(service: InCallService?) {
        inCallService = service
    }

    fun attach(call: Call) {
        currentCall = call
        publish(call)
    }

    fun update(call: Call) {
        if (currentCall === call) publish(call)
    }

    fun detach(call: Call) {
        if (currentCall === call) {
            currentCall = null
            mutableState.value = CallUiState()
        }
    }

    private fun publish(call: Call) {
        val number = call.details.handle?.schemeSpecificPart
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown caller"

        mutableState.value = mutableState.value.copy(
            call = call,
            number = number,
            state = call.state
        )
    }

    fun answer() {
        currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline() {
        currentCall?.reject(false, null)
    }

    fun endCall() {
        currentCall?.disconnect()
    }

    fun setMuted(muted: Boolean) {
        inCallService?.setMuted(muted)
        mutableState.value = mutableState.value.copy(muted = muted)
    }

    fun toggleSpeaker() {
        val next = !mutableState.value.speaker
        inCallService?.setAudioRoute(
            if (next) CallAudioState.ROUTE_SPEAKER
            else CallAudioState.ROUTE_EARPIECE
        )
        mutableState.value = mutableState.value.copy(speaker = next)
    }
}
