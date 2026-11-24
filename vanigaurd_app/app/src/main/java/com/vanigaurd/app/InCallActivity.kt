
package com.vanigaurd.app

import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        handleAction(intent?.action)

        setContent {
            val ui by CallUiController.state.collectAsState()

            var hasSeenCall by remember {
                mutableStateOf(ui.call != null)
            }

            // Close the activity after the call disconnects.
            LaunchedEffect(ui.call, ui.state) {
                if (ui.call != null) {
                    hasSeenCall = true
                }

                if (
                    hasSeenCall &&
                    (ui.call == null || ui.state == Call.STATE_DISCONNECTED)
                ) {
                    finish()
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val ringing = ui.state == Call.STATE_RINGING
                    val active = ui.state == Call.STATE_ACTIVE

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when {
                                ringing -> "Incoming call"
                                active -> "Call in progress"
                                else -> "Call"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )

                        Spacer(Modifier.height(20.dp))

                        Text(
                            text = ui.number,
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = when (ui.state) {
                                Call.STATE_RINGING -> "Ringing"
                                Call.STATE_DIALING -> "Calling…"
                                Call.STATE_CONNECTING -> "Connecting…"
                                Call.STATE_ACTIVE -> "Connected"
                                Call.STATE_HOLDING -> "On hold"
                                Call.STATE_DISCONNECTED -> "Call ended"
                                else -> "Connecting…"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(Modifier.height(44.dp))

                        if (ringing) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Button(
                                    onClick = {
                                        CallUiController.decline()
                                        finish()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Decline")
                                }

                                Button(
                                    onClick = {
                                        CallUiController.answer()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = CircleShape
                                ) {
                                    Text("Answer")
                                }
                            }
                        } else if (
                            active || ui.state == Call.STATE_HOLDING
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        CallUiController.setMuted(!ui.muted)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (ui.muted) "Unmute" else "Mute")
                                }

                                Button(
                                    onClick = {
                                        CallUiController.toggleSpeaker()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (ui.speaker) "Earpiece" else "Speaker"
                                    )
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    CallUiController.endCall()
                                    finish()
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("End call")
                            }
                        } else {
                            Text("Waiting for call state…")
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent.action)
    }

    private fun handleAction(action: String?) {
        when (action) {
            ACTION_ANSWER -> CallUiController.answer()

            ACTION_DECLINE -> {
                CallUiController.decline()
                finish()
            }

            ACTION_END -> {
                CallUiController.endCall()
                finish()
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.vanigaurd.app.SHOW_CALL"
        const val ACTION_ANSWER = "com.vanigaurd.app.ANSWER_CALL"
        const val ACTION_DECLINE = "com.vanigaurd.app.DECLINE_CALL"
        const val ACTION_END = "com.vanigaurd.app.END_CALL"
    }
}
