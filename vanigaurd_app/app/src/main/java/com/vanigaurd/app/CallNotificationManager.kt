
package com.vanigaurd.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.Call.Details
import android.telecom.Call.STATE_RINGING
import android.telecom.Call.STATE_ACTIVE
import android.telecom.Call.STATE_DIALING
import android.telecom.Call.STATE_CONNECTING
import android.telecom.Call.STATE_HOLDING
import android.graphics.drawable.Icon
import android.app.Person

object CallNotificationManager {
    private const val CHANNEL_ID = "vaniguard_calls"
    private const val NOTIFICATION_ID = 7701

    fun show(context: Context, call: Call) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VaniGuard calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and ongoing call controls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val number = call.details.handle?.schemeSpecificPart
            ?.takeIf { it.isNotBlank() } ?: "Unknown caller"

        fun activityIntent(action: String): PendingIntent {
            val intent = Intent(context, InCallActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val openIntent = activityIntent(InCallActivity.ACTION_SHOW)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Person.Builder().setName(number).build()
        } else null

        builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (call.state == STATE_RINGING) "Incoming call"
                else "VaniGuard call"
            )
            .setContentText(number)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(call.state != Call.STATE_DISCONNECTED)
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, call.state == STATE_RINGING)
            .setAutoCancel(false)

        if (call.state == STATE_RINGING) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                activityIntent(InCallActivity.ACTION_DECLINE)
            )
            builder.addAction(
                android.R.drawable.ic_menu_call,
                "Answer",
                activityIntent(InCallActivity.ACTION_ANSWER)
            )
        } else if (
            call.state == STATE_ACTIVE ||
            call.state == STATE_DIALING ||
            call.state == STATE_CONNECTING ||
            call.state == STATE_HOLDING
        ) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End call",
                activityIntent(InCallActivity.ACTION_END)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && person != null) {
            val style = if (call.state == STATE_RINGING) {
                Notification.CallStyle.forIncomingCall(
                    person,
                    activityIntent(InCallActivity.ACTION_DECLINE),
                    activityIntent(InCallActivity.ACTION_ANSWER)
                )
            } else {
                Notification.CallStyle.forOngoingCall(
                    person,
                    activityIntent(InCallActivity.ACTION_END)
                )
            }
            builder.setStyle(style)
        }

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
