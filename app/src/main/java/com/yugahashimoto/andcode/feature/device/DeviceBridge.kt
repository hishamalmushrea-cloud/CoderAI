package com.yugahashimoto.andcode.feature.device

import android.content.Context
import android.util.Log
import com.yugahashimoto.andcode.feature.accessibility.AndCodeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * File-based request/response bridge connecting the guest Python MCP server (`andcode-device-mcp.py`)
 * running inside PRoot Linux to the native Android framework via [AndroidDeviceBridge].
 *
 * Requests arrive under `[workspaceDir]/.and-code/device-bridge/pending/<id>.json`.
 * Responses are written to `[workspaceDir]/.and-code/device-bridge/responses/<id>.json`.
 */
class DeviceBridge(
    private val context: Context,
    private val workspaceDir: File,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val bridgeDir: File = File(workspaceDir, BRIDGE_RELATIVE_PATH)
    private val deviceBridge = AndroidDeviceBridge(context)

    suspend fun run() {
        while (true) {
            runCatching { pollOnce() }
            delay(pollIntervalMillis)
        }
    }

    suspend fun pollOnce() = withContext(Dispatchers.IO) {
        if (!bridgeDir.isDirectory) return@withContext
        val pendingDir = File(bridgeDir, "pending")
        val responsesDir = File(bridgeDir, "responses")
        val now = clock()

        val pendingFiles = pendingDir.listFiles().orEmpty()
            .filter { it.extension == "json" }
            .sortedBy { it.name }

        for (file in pendingFiles) {
            handlePending(file, responsesDir, now)
        }
        pruneStale(pendingDir, now)
        pruneStale(responsesDir, now)
    }

    private fun handlePending(file: File, responsesDir: File, now: Long) {
        val requestId = file.nameWithoutExtension
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) {
            if (now - file.lastModified() > staleAfterMillis) file.delete()
            return
        }

        val request = runCatching { JSONObject(text) }.getOrNull()
        if (request == null) {
            file.delete()
            return
        }

        val op = request.optString("op")
        val args = request.optJSONObject("args") ?: JSONObject()

        val response = runCatching { dispatch(op, args) }
            .fold(
                onSuccess = { data ->
                    JSONObject().apply {
                        put("ok", true)
                        put("data", data)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "DeviceBridge operation '$op' failed", error)
                    JSONObject().apply {
                        put("ok", false)
                        put("error", error.message ?: "Operation failed")
                    }
                }
            )

        responsesDir.mkdirs()
        val temp = File(responsesDir, "$requestId.json.tmp")
        val target = File(responsesDir, "$requestId.json")
        temp.writeText(response.toString())
        temp.renameTo(target)
        file.delete()
    }

    private fun dispatch(op: String, args: JSONObject): Any {
        return when (op) {
            "launch_app" -> {
                val app = args.getString("appName")
                deviceBridge.launchApp(app)
            }
            "make_call" -> {
                val recipient = args.getString("recipient")
                val direct = args.optBoolean("directCall", true)
                deviceBridge.makePhoneCall(recipient, direct)
            }
            "search_contacts" -> {
                val query = args.optString("query", "")
                deviceBridge.searchContacts(query)
            }
            "send_whatsapp" -> {
                val recipient = args.getString("recipient")
                val message = args.getString("message")
                val autoClick = args.optBoolean("autoClickSend", true)
                deviceBridge.sendWhatsAppMessage(recipient, message, autoClick)
            }
            "send_sms" -> {
                val recipient = args.getString("recipient")
                val message = args.getString("message")
                deviceBridge.sendSms(recipient, message)
            }
            "search_media" -> {
                val query = args.optString("query", "")
                val mediaType = args.optString("mediaType", null)
                val limit = args.optInt("limit", 15)
                deviceBridge.searchMedia(query, mediaType, limit)
            }
            "open_file" -> {
                val path = args.getString("path")
                deviceBridge.openFile(path)
            }
            "set_timer" -> {
                val seconds = args.getInt("seconds")
                val label = args.optString("label", "Timer")
                deviceBridge.setTimer(seconds, label)
            }
            "schedule_action" -> {
                val delayMinutes = args.getLong("delayMinutes")
                val actionType = args.getString("actionType")
                val params = args.optJSONObject("parameters") ?: JSONObject()
                deviceBridge.scheduleDelayedAction(delayMinutes, actionType, params)
            }
            "control_hardware" -> {
                val feature = args.getString("feature")
                val state = args.getBoolean("state")
                deviceBridge.controlHardware(feature, state)
            }
            "get_device_status" -> {
                deviceBridge.getDeviceStatus()
            }
            "accessibility_click" -> {
                val target = args.getString("target")
                val ok = AndCodeAccessibilityService.instance?.clickElementByTextOrId(target) ?: false
                JSONObject().apply {
                    put("status", if (ok) "success" else "not_found")
                    put("target", target)
                }
            }
            "accessibility_type" -> {
                val text = args.getString("text")
                val ok = AndCodeAccessibilityService.instance?.typeIntoFocusedElement(text) ?: false
                JSONObject().apply {
                    put("status", if (ok) "success" else "failed")
                    put("text", text)
                }
            }
            "accessibility_dump_screen" -> {
                val nodes = AndCodeAccessibilityService.instance?.dumpCurrentScreen() ?: emptyList()
                JSONObject().apply {
                    put("status", "success")
                    put("elements", nodes)
                }
            }
            else -> throw IllegalArgumentException("Unknown device operation: $op")
        }
    }

    private fun pruneStale(dir: File, now: Long) {
        val files = dir.listFiles().orEmpty()
        for (f in files) {
            if (now - f.lastModified() > staleAfterMillis) {
                f.delete()
            }
        }
    }

    companion object {
        private const val TAG = "DeviceBridge"
        const val BRIDGE_RELATIVE_PATH = ".and-code/device-bridge"
        private const val POLL_INTERVAL_MILLIS = 250L
        private const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    }
}
