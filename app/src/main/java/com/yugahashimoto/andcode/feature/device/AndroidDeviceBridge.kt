package com.yugahashimoto.andcode.feature.device

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yugahashimoto.andcode.feature.accessibility.AndCodeAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles on-device phone operations invoked by the AI Agent.
 * Exposes core assistant actions: app launching, calling, WhatsApp messaging, SMS,
 * media/file searching, timers, delayed action scheduling, hardware control, and device telemetry.
 */
class AndroidDeviceBridge(private val context: Context) {

    companion object {
        private const val TAG = "AndroidDeviceBridge"

        // Common package mappings for voice recognition in multiple languages (English / Arabic)
        private val KNOWN_APP_ALIASES = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "واتساب" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "واتس" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "youtube" to listOf("com.google.android.youtube"),
            "يوتيوب" to listOf("com.google.android.youtube"),
            "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
            "تيليجرام" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
            "تليجرام" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
            "facebook" to listOf("com.facebook.katana"),
            "فيسبوك" to listOf("com.facebook.katana"),
            "فيس" to listOf("com.facebook.katana"),
            "instagram" to listOf("com.instagram.android"),
            "انستغرام" to listOf("com.instagram.android"),
            "إنستغرام" to listOf("com.instagram.android"),
            "انستا" to listOf("com.instagram.android"),
            "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera"),
            "كاميرا" to listOf("com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera"),
            "الكاميرا" to listOf("com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera"),
            "gallery" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
            "الاستوديو" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
            "الصور" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
            "chrome" to listOf("com.android.chrome"),
            "كروم" to listOf("com.android.chrome"),
            "متصفح" to listOf("com.android.chrome"),
            "maps" to listOf("com.google.android.apps.maps"),
            "خرائط" to listOf("com.google.android.apps.maps"),
            "الخرائط" to listOf("com.google.android.apps.maps"),
            "settings" to listOf("com.android.settings"),
            "الإعدادات" to listOf("com.android.settings"),
            "الاعدادات" to listOf("com.android.settings"),
            "ضبط" to listOf("com.android.settings"),
            "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "الهاتف" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "اتصال" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "gmail" to listOf("com.google.android.gm"),
            "جيميل" to listOf("com.google.android.gm"),
            "بريد" to listOf("com.google.android.gm"),
            "clock" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"),
            "الساعة" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"),
            "منبه" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"),
            "calculator" to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator"),
            "الحاسبة" to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator"),
            "حاسبة" to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator")
        )
    }

    /**
     * Resolves and launches an application by name or package identifier.
     */
    fun launchApp(appNameOrPackage: String): JSONObject {
        val pm = context.packageManager
        val trimmed = appNameOrPackage.trim().lowercase(Locale.ROOT)

        // 1. Direct package check
        var launchIntent = pm.getLaunchIntentForPackage(trimmed)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return JSONObject().apply {
                put("status", "success")
                put("message", "Launched package: $trimmed")
                put("package", trimmed)
            }
        }

        // 2. Check alias map
        val candidatePackages = KNOWN_APP_ALIASES[trimmed] ?: emptyList()
        for (pkg in candidatePackages) {
            launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return JSONObject().apply {
                    put("status", "success")
                    put("message", "Launched app for alias '$trimmed': $pkg")
                    put("package", pkg)
                }
            }
        }

        // 3. Scan installed launcher activities
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        var bestPkg: String? = null
        var bestLabel: String? = null

        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
            val pkg = info.activityInfo.packageName
            if (label == trimmed || pkg.contains(trimmed)) {
                bestPkg = pkg
                bestLabel = label
                break
            } else if (label.contains(trimmed) || trimmed.contains(label)) {
                if (bestPkg == null) {
                    bestPkg = pkg
                    bestLabel = label
                }
            }
        }

        if (bestPkg != null) {
            val intent = pm.getLaunchIntentForPackage(bestPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                context.startActivity(intent)
                return JSONObject().apply {
                    put("status", "success")
                    put("message", "Launched app: $bestLabel ($bestPkg)")
                    put("package", bestPkg)
                }
            }
        }

        return JSONObject().apply {
            put("status", "error")
            put("error", "Application not found matching '$appNameOrPackage'")
        }
    }

    /**
     * Searches contacts matching query and retrieves display names and phone numbers.
     */
    fun searchContacts(query: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val trimmed = query.trim()
        val resolver: ContentResolver = context.contentResolver

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return results
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val selectionArgs = arrayOf("%$trimmed%", "%$trimmed%")

        val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: ""
                val number = it.getString(numIndex) ?: ""
                results.add(JSONObject().apply {
                    put("name", name)
                    put("number", number)
                })
            }
        }

        return results
    }

    /**
     * Resolves recipient and initiates a phone call or opens the dialer.
     */
    fun makePhoneCall(recipient: String, directCall: Boolean = true): JSONObject {
        var phoneNumber = recipient.filter { it.isDigit() || it == '+' }
        var contactName: String? = null

        if (phoneNumber.length < 3) {
            // Recipient is likely a contact name, search contacts
            val contacts = searchContacts(recipient)
            if (contacts.isNotEmpty()) {
                val best = contacts[0]
                contactName = best.getString("name")
                phoneNumber = best.getString("number").filter { it.isDigit() || it == '+' }
            } else {
                return JSONObject().apply {
                    put("status", "error")
                    put("error", "Could not find phone number or contact matching '$recipient'")
                }
            }
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (directCall && hasCallPermission) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            return JSONObject().apply {
                put("status", "success")
                put("message", "Calling ${contactName ?: phoneNumber} ($phoneNumber)")
                put("phoneNumber", phoneNumber)
                if (contactName != null) put("contactName", contactName)
                put("directCall", directCall && hasCallPermission)
            }
        } catch (e: Exception) {
            return JSONObject().apply {
                put("status", "error")
                put("error", "Failed to initiate call: ${e.message}")
            }
        }
    }

    /**
     * Sends or opens a WhatsApp message to a contact or phone number.
     * Optionally triggers auto-send if accessibility service is enabled.
     */
    fun sendWhatsAppMessage(recipient: String, message: String, autoClickSend: Boolean = true): JSONObject {
        var phoneNumber = recipient.filter { it.isDigit() || it == '+' }
        var contactName: String? = null

        if (phoneNumber.length < 5) {
            val contacts = searchContacts(recipient)
            if (contacts.isNotEmpty()) {
                val best = contacts[0]
                contactName = best.getString("name")
                phoneNumber = best.getString("number").filter { it.isDigit() || it == '+' }
            }
        }

        // Clean digits only for WhatsApp web/direct API
        val cleanDigits = phoneNumber.filter { it.isDigit() }

        val uri = if (cleanDigits.isNotEmpty()) {
            Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits&text=${Uri.encode(message)}")
        } else {
            Uri.parse("whatsapp://send?text=${Uri.encode(message)}")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)

            // If Accessibility Service is running, request it to auto-click the send button after WhatsApp opens
            if (autoClickSend && AndCodeAccessibilityService.isRunning()) {
                AndCodeAccessibilityService.instance?.scheduleClickSend(delayMs = 1500)
            }

            return JSONObject().apply {
                put("status", "success")
                put("message", "Opened WhatsApp with message to ${contactName ?: phoneNumber}")
                put("recipient", contactName ?: phoneNumber)
                put("text", message)
                put("autoClickRequested", autoClickSend && AndCodeAccessibilityService.isRunning())
            }
        } catch (e: Exception) {
            // Fallback to generic view
            intent.setPackage(null)
            return try {
                context.startActivity(intent)
                JSONObject().apply {
                    put("status", "success")
                    put("message", "Opened messaging app with text")
                }
            } catch (err: Exception) {
                JSONObject().apply {
                    put("status", "error")
                    put("error", "WhatsApp not installed or could not launch: ${err.message}")
                }
            }
        }
    }

    /**
     * Prepares and opens SMS composer for a recipient with pre-filled message.
     */
    fun sendSms(recipient: String, message: String): JSONObject {
        var phoneNumber = recipient.filter { it.isDigit() || it == '+' }
        if (phoneNumber.length < 3) {
            val contacts = searchContacts(recipient)
            if (contacts.isNotEmpty()) {
                phoneNumber = contacts[0].getString("number").filter { it.isDigit() || it == '+' }
            }
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("status", "success")
                put("message", "Opened SMS composer for $phoneNumber")
                put("phoneNumber", phoneNumber)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", "Unable to open SMS composer: ${e.message}")
            }
        }
    }

    /**
     * Search for videos, images, audio, or general files matching a query.
     * Combines MediaStore query and direct filesystem search for maximum coverage.
     */
    fun searchMedia(query: String, mediaType: String? = null, limit: Int = 15): JSONObject {
        val results = JSONArray()
        val queryLower = query.lowercase(Locale.ROOT)
        val resolver = context.contentResolver

        val uri = when (mediaType?.lowercase(Locale.ROOT)) {
            "video", "videos", "فيديو", "فيديوهات" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "image", "images", "صورة", "صور" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "audio", "sound", "موسيقى", "صوت" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $limit"

        try {
            val cursor = resolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                val nameCol = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                while (it.moveToNext() && results.length() < limit) {
                    val name = it.getString(nameCol) ?: "Unknown"
                    val path = it.getString(pathCol) ?: ""
                    val sizeBytes = it.getLong(sizeCol)
                    val dateModifiedSec = it.getLong(dateCol)
                    val mime = it.getString(mimeCol) ?: ""

                    results.put(JSONObject().apply {
                        put("name", name)
                        put("path", path)
                        put("size", formatFileSize(sizeBytes))
                        put("sizeBytes", sizeBytes)
                        put("dateModified", dateFormat.format(Date(dateModifiedSec * 1000L)))
                        put("mimeType", mime)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore search error", e)
        }

        // If MediaStore returned few items, also search common storage paths directly (/sdcard/Download, /sdcard/DCIM, etc.)
        if (results.length() < limit) {
            val commonDirs = listOf(
                File(Environment.getExternalStorageDirectory(), "Download"),
                File(Environment.getExternalStorageDirectory(), "DCIM"),
                File(Environment.getExternalStorageDirectory(), "Movies"),
                File(Environment.getExternalStorageDirectory(), "Documents")
            )

            for (dir in commonDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                searchFilesRecursive(dir, queryLower, mediaType, results, limit)
                if (results.length() >= limit) break
            }
        }

        return JSONObject().apply {
            put("status", "success")
            put("count", results.length())
            put("query", query)
            put("results", results)
        }
    }

    private fun searchFilesRecursive(
        dir: File,
        queryLower: String,
        mediaType: String?,
        results: JSONArray,
        limit: Int
    ) {
        val files = dir.listFiles() ?: return
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (file in files) {
            if (results.length() >= limit) return
            if (file.isDirectory) {
                // Skip hidden folders
                if (!file.name.startsWith(".")) {
                    searchFilesRecursive(file, queryLower, mediaType, results, limit)
                }
            } else {
                val nameLower = file.name.lowercase(Locale.ROOT)
                if (nameLower.contains(queryLower)) {
                    if (mediaTypeMatches(nameLower, mediaType)) {
                        // Check not already added
                        var alreadyAdded = false
                        for (i in 0 until results.length()) {
                            if (results.getJSONObject(i).optString("path") == file.absolutePath) {
                                alreadyAdded = true
                                break
                            }
                        }
                        if (!alreadyAdded) {
                            results.put(JSONObject().apply {
                                put("name", file.name)
                                put("path", file.absolutePath)
                                put("size", formatFileSize(file.length()))
                                put("sizeBytes", file.length())
                                put("dateModified", dateFormat.format(Date(file.lastModified())))
                                put("mimeType", getMimeType(file.extension))
                            })
                        }
                    }
                }
            }
        }
    }

    private fun mediaTypeMatches(filename: String, mediaType: String?): Boolean {
        if (mediaType.isNullOrBlank()) return true
        val ext = filename.substringAfterLast('.', "")
        return when (mediaType.lowercase(Locale.ROOT)) {
            "video", "videos", "فيديو" -> ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv")
            "image", "images", "صورة", "صور" -> ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")
            "audio", "sound", "صوت", "موسيقى" -> ext in setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")
            else -> true
        }
    }

    private fun getMimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        else -> "*/*"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Opens a local file in its default system viewer.
     */
    fun openFile(filePath: String): JSONObject {
        val file = File(filePath)
        if (!file.exists()) {
            return JSONObject().apply {
                put("status", "error")
                put("error", "File does not exist: $filePath")
            }
        }

        val mimeType = getMimeType(file.extension)
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("status", "success")
                put("message", "Opened file: ${file.name}")
                put("path", filePath)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", "Failed to open file: ${e.message}")
            }
        }
    }

    /**
     * Sets a countdown timer via the system clock app.
     */
    fun setTimer(seconds: Int, label: String = "Timer"): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("status", "success")
                put("message", "Timer set for $seconds seconds ($label)")
                put("seconds", seconds)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", "Clock app did not support setting timer: ${e.message}")
            }
        }
    }

    /**
     * Schedules a delayed action (e.g. send WhatsApp after 10 minutes).
     * Schedules an alarm via AlarmManager with payload details.
     */
    fun scheduleDelayedAction(
        delayMinutes: Long,
        actionType: String,
        parameters: JSONObject
    ): JSONObject {
        val triggerAtMillis = SystemClock.elapsedRealtime() + (delayMinutes * 60 * 1000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, DelayedActionReceiver::class.java).apply {
            action = ACTION_EXECUTE_DELAYED
            putExtra(EXTRA_ACTION_TYPE, actionType)
            putExtra(EXTRA_ACTION_PARAMS, parameters.toString())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            JSONObject().apply {
                put("status", "success")
                put("message", "Scheduled action '$actionType' in $delayMinutes minutes")
                put("delayMinutes", delayMinutes)
                put("actionType", actionType)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", "Failed to schedule action: ${e.message}")
            }
        }
    }

    /**
     * Toggles hardware devices such as the flashlight (torch).
     */
    fun controlHardware(feature: String, state: Boolean): JSONObject {
        return when (feature.lowercase(Locale.ROOT)) {
            "flashlight", "torch", "مصباح", "فلاش", "كشاف" -> {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                try {
                    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (cameraId != null) {
                        cameraManager.setTorchMode(cameraId, state)
                        JSONObject().apply {
                            put("status", "success")
                            put("message", if (state) "Flashlight turned ON" else "Flashlight turned OFF")
                            put("feature", "flashlight")
                            put("state", state)
                        }
                    } else {
                        JSONObject().apply {
                            put("status", "error")
                            put("error", "No camera with flash available on this device")
                        }
                    }
                } catch (e: CameraAccessException) {
                    JSONObject().apply {
                        put("status", "error")
                        put("error", "Camera access error: ${e.message}")
                    }
                }
            }
            else -> JSONObject().apply {
                put("status", "error")
                put("error", "Unknown hardware feature: $feature")
            }
        }
    }

    /**
     * Queries battery, storage, time, and device information.
     */
    fun getDeviceStatus(): JSONObject {
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return JSONObject().apply {
            put("status", "success")
            put("batteryPercent", batteryPct)
            put("isCharging", isCharging)
            put("freeStorage", formatFileSize(freeBytes))
            put("totalStorage", formatFileSize(totalBytes))
            put("currentTime", dateFormat.format(Date()))
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
            put("accessibilityServiceRunning", AndCodeAccessibilityService.isRunning())
        }
    }
}
