
package com.vanigaurd.app

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
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
                    CallSessionRuntime.start(call)
                }

                if (state == Call.STATE_DISCONNECTED) {
                    CallSessionRuntime.finish(call)
                    CallNotificationManager.cancel(this@VaniGuardInCallService)
                    CallUiController.detach(call)
                } else {
                    CallNotificationManager.show(this@VaniGuardInCallService, call)
                }
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                CallUiController.update(call)
                CallNotificationManager.show(this@VaniGuardInCallService, call)
            }
        }

        call.registerCallback(callback)
        callbacks[call] = callback

        if (call.state == Call.STATE_ACTIVE) {
            CallSessionRuntime.start(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Tracker {
        @Volatile var sessionId: String? = null
        @Volatile var ended: Boolean = false
        @Volatile var finishStarted: Boolean = false
        var startJob: kotlinx.coroutines.Job? = null
    }

    private val trackers = ConcurrentHashMap<Call, Tracker>()

    fun start(call: Call) {
        val tracker = trackers.computeIfAbsent(call) { Tracker() }

        synchronized(tracker) {
            if (tracker.startJob != null || tracker.sessionId != null || tracker.ended) {
                return
            }

            tracker.startJob = scope.launch {
                try {
                    val session = LiveAudioApi.createSession()

                    val shouldFinish: Boolean
                    synchronized(tracker) {
                        tracker.sessionId = session.sessionId
                        shouldFinish = tracker.ended
                    }

                    Log.d(TAG, "Automatic call session created.")

                    if (shouldFinish) {
                        finishTracker(call, tracker)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to create call session", e)
                    trackers.remove(call, tracker)
                }
            }
        }
    }

    fun finish(call: Call) {
        val tracker = trackers[call] ?: return
        synchronized(tracker) {
            tracker.ended = true
        }
        finishTracker(call, tracker)
    }

    private fun finishTracker(call: Call, tracker: Tracker) {
        val sessionId: String

        synchronized(tracker) {
            if (!tracker.ended || tracker.finishStarted) return
            sessionId = tracker.sessionId ?: return
            tracker.finishStarted = true
        }

        scope.launch {
            try {
                val report = LiveAudioApi.finishSession(sessionId)
                Log.d(
                    TAG,
                    "Call report finalized. Risk=${report.riskLevel}, " +
                            "Segments=${report.segmentCount}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to finalize call report", e)
            } finally {
                trackers.remove(call, tracker)
            }
        }
    }

    private const val TAG = "VaniGuardSession"
}
