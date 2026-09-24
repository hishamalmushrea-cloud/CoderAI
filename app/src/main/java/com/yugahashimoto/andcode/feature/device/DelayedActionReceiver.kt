package com.yugahashimoto.andcode.feature.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import org.json.JSONObject

const val ACTION_EXECUTE_DELAYED = "com.yugahashimoto.andcode.action.EXECUTE_DELAYED"
const val EXTRA_ACTION_TYPE = "action_type"
const val EXTRA_ACTION_PARAMS = "action_params"

/**
 * BroadcastReceiver triggered by AlarmManager when a user-scheduled delayed action fires
 * (e.g., "send message after 10 minutes").
 */
class DelayedActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_DELAYED) return

        val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: return
        val paramsJsonStr = intent.getStringExtra(EXTRA_ACTION_PARAMS) ?: "{}"
        val params = try {
            JSONObject(paramsJsonStr)
        } catch (e: Exception) {
            JSONObject()
        }

        Log.i("DelayedActionReceiver", "Executing delayed action: $actionType with params: $params")

        val bridge = AndroidDeviceBridge(context)

        when (actionType) {
            "send_whatsapp", "whatsapp" -> {
                val recipient = params.optString("recipient", "")
                val message = params.optString("message", "")
                if (recipient.isNotEmpty()) {
                    bridge.sendWhatsAppMessage(recipient, message)
                    showNotification(
                        context,
                        "WhatsApp Message Triggered",
                        "Sending message to $recipient: $message"
                    )
                }
            }
            "send_sms", "sms" -> {
                val recipient = params.optString("recipient", "")
                val message = params.optString("message", "")
                if (recipient.isNotEmpty()) {
                    bridge.sendSms(recipient, message)
                    showNotification(
                        context,
                        "SMS Message Triggered",
                        "Sending SMS to $recipient: $message"
                    )
                }
            }
            "make_call", "call" -> {
                val recipient = params.optString("recipient", "")
                if (recipient.isNotEmpty()) {
                    bridge.makePhoneCall(recipient, directCall = false)
                    showNotification(
                        context,
                        "Scheduled Call",
                        "Calling $recipient"
                    )
                }
            }
            "reminder", "notify" -> {
                val title = params.optString("title", "Reminder")
                val text = params.optString("text", "Your scheduled reminder")
                showNotification(context, title, text)
            }
        }
    }

    private fun showNotification(context: Context, title: String, text: String) {
        val channelId = "delayed_actions_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Scheduled Actions",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
