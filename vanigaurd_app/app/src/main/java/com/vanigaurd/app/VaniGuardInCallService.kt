
package com.vanigaurd.app

import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.vanigaurd.app.audio.CallAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class VaniGuardInCallService : InCallService() {

    private val callbacks = ConcurrentHashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallUiController.bindService(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        Log.d(TAG, "Call added. Direction=${call.details.callDirection}")

        CallUiController.attach(call)
        CallNotificationManager.show(this, call)

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                Log.d(TAG, "Call state changed: $state")

                CallUiController.update(call)

                if (state == Call.STATE_ACTIVE) {
                    CallSessionRuntime.start(this@VaniGuardInCallService, call)
                }

                if (state == Call.STATE_DISCONNECTED) {
                    CallSessionRuntime.finish(call)
                    CallNotificationManager.cancel(this@VaniGuardInCallService)
                    CallUiController.detach(call)
                } else {
                    CallNotificationManager.show(this@VaniGuardInCallService, call)
                }
            }

            override fun onDetailsChanged(
                call: Call,
                details: Call.Details
            ) {
                CallUiController.update(call)
                CallNotificationManager.show(this@VaniGuardInCallService, call)
            }
        }

        call.registerCallback(callback)
        callbacks[call] = callback

        if (call.state == Call.STATE_ACTIVE) {
            CallSessionRuntime.start(this, call)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let {
            call.unregisterCallback(it)
        }

        CallSessionRuntime.finish(call)
        CallUiController.detach(call)

        if (calls.isEmpty()) {
            CallNotificationManager.cancel(this)
        }

        Log.d(TAG, "Call removed.")
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        CallUiController.bindService(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VaniGuardInCall"
    }
}

private object CallSessionRuntime {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Tracker {
        @Volatile
        var sessionId: String? = null

        @Volatile
        var ended: Boolean = false

        @Volatile
        var finishStarted: Boolean = false

        var startJob: kotlinx.coroutines.Job? = null

        var recorder: CallAudioRecorder? = null
    }

    private val trackers = ConcurrentHashMap<Call, Tracker>()

    fun start(context: Context, call: Call) {
        val tracker = trackers.computeIfAbsent(call) {
            Tracker()
        }

        synchronized(tracker) {
            if (
                tracker.startJob != null ||
                tracker.sessionId != null ||
                tracker.ended
            ) {
                return
            }

            Log.d(TAG, "Starting backend session creation.")

            tracker.startJob = scope.launch {
                try {
                    val session = LiveAudioApi.createSession()

                    var shouldFinish = false

                    synchronized(tracker) {
                        tracker.sessionId = session.sessionId
                        shouldFinish = tracker.ended

                        if (!shouldFinish) {
                            val audioRecorder = CallAudioRecorder(
                                context = context.applicationContext,
                                sessionId = session.sessionId,
                                onAnalysis = { result ->
                                    val analysis = result.analysis

                                    Log.i(
                                        TAG,
                                        "Live call analysis: " +
                                                "prediction=${analysis.prediction}, " +
                                                "risk=${analysis.riskLevel}, " +
                                                "score=${analysis.fraudScore}"
                                    )

                                    if (
                                        analysis.riskLevel.equals(
                                            "HIGH",
                                            ignoreCase = true
                                        )
                                    ) {
                                        Log.w(
                                            TAG,
                                            "HIGH FRAUD RISK DETECTED DURING CALL"
                                        )
                                    }
                                },
                                onStatus = { message ->
                                    Log.d(TAG, "Recorder: $message")
                                }
                            )

                            tracker.recorder = audioRecorder

                            if (audioRecorder.start()) {
                                Log.i(
                                    TAG,
                                    "Automatic call audio capture started. " +
                                            "Session=${session.sessionId}"
                                )
                            } else {
                                Log.e(
                                    TAG,
                                    "Automatic audio capture could not start. " +
                                            "Session=${session.sessionId}"
                                )
                            }
                        }
                    }

                    Log.d(
                        TAG,
                        "Backend session created: ${session.sessionId}"
                    )

                    if (shouldFinish) {
                        Log.d(
                            TAG,
                            "Call already ended. Finalizing session."
                        )
                        finishTracker(call, tracker)
                    }

                } catch (error: Exception) {
                    Log.e(TAG, "Unable to create call session", error)
                    trackers.remove(call, tracker)
                }
            }
        }
    }

    fun finish(call: Call) {
        val tracker = trackers[call]

        if (tracker == null) {
            Log.w(TAG, "Finish requested, but no session tracker exists.")
            return
        }

        synchronized(tracker) {
            tracker.ended = true
        }

        Log.d(TAG, "Call ended. Recorder shutdown requested.")

        finishTracker(call, tracker)
    }

    private fun finishTracker(
        call: Call,
        tracker: Tracker
    ) {
        val sessionId: String

        synchronized(tracker) {
            if (!tracker.ended || tracker.finishStarted) {
                Log.d(
                    TAG,
                    "Finish skipped: already running or call not ended."
                )
                return
            }

            sessionId = tracker.sessionId ?: run {
                Log.d(
                    TAG,
                    "Waiting for backend session ID before finalization."
                )
                return
            }

            tracker.finishStarted = true
        }

        scope.launch {
            try {
                // Stop capture and wait for queued audio uploads first.
                val recorder = synchronized(tracker) {
                    tracker.recorder
                }

                if (recorder != null) {
                    Log.d(TAG, "Stopping recorder and flushing audio.")
                    recorder.stopAndFlush()
                }

                Log.d(
                    TAG,
                    "Sending finish request for session: $sessionId"
                )

                val report = LiveAudioApi.finishSession(sessionId)

                Log.i(
                    TAG,
                    "Call report finalized successfully. " +
                            "Session=$sessionId, " +
                            "Risk=${report.finalAnalysis.riskLevel}, " +
                            "Score=${report.finalAnalysis.fraudScore}, " +
                            "Segments=${report.segmentCount}"
                )

            } catch (error: Exception) {
                synchronized(tracker) {
                    tracker.finishStarted = false
                }

                Log.e(
                    TAG,
                    "Unable to finalize call report: $sessionId",
                    error
                )

                return@launch
            }

            synchronized(tracker) {
                trackers.remove(call, tracker)
            }
        }
    }

    private const val TAG = "VaniGuardSession"
}
