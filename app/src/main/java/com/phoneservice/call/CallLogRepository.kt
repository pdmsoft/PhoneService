package com.phoneservice.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CallLogRepository {
    private const val tag = "CallArchive"
    private const val fileName = "calls.json"
    private const val maxStored = 20_000
    private const val overlapMillis = 6 * 60 * 60 * 1000L
    private const val observerDelayMillis = 3_000L
    private val lock = ReentrantLock()
    private val watchLock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val syncThread by lazy(LazyThreadSafetyMode.NONE) {
        HandlerThread("call-archive").apply { start() }
    }
    private val syncHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(syncThread.looper)
    }
    private val followUpTask = Runnable { snapshotCallLog() }
    private var immediateQueued = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var observer: android.database.ContentObserver? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun addOnChanged(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeOnChanged(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun sync(context: Context): CallArchive {
        if (!hasPermission(context)) return load(context)
        val existing = load(context)
        val fresh = try {
            queryRecent(context, existing.savedAtMillis)
        } catch (_: SecurityException) {
            return existing
        }
        val archive = lock.withLock {
            val stored = loadUnlocked(context)
            val merged = CallArchive(
                calls = mergeCalls(fresh, stored.calls, maxStored),
                savedAtMillis = System.currentTimeMillis(),
            )
            saveUnlocked(context, merged)
            merged
        }
        notifyChanged()
        return archive
    }

    fun load(context: Context): CallArchive = lock.withLock { loadUnlocked(context) }

    fun unwatch(context: Context) {
        synchronized(watchLock) {
            val contentObserver = observer ?: return
            context.applicationContext.contentResolver.unregisterContentObserver(contentObserver)
            observer = null
            appContext = null
            immediateQueued = false
            syncHandler.removeCallbacksAndMessages(null)
        }
    }

    fun watch(context: Context) {
        if (!hasPermission(context)) return
        val applicationContext = context.applicationContext
        synchronized(watchLock) {
            if (observer != null) return
            appContext = applicationContext
            val contentObserver = object : android.database.ContentObserver(syncHandler) {
                override fun onChange(selfChange: Boolean) {
                    if (!immediateQueued) {
                        immediateQueued = true
                        syncHandler.post {
                            immediateQueued = false
                            snapshotCallLog()
                        }
                    }
                    syncHandler.removeCallbacks(followUpTask)
                    syncHandler.postDelayed(followUpTask, observerDelayMillis)
                }
            }
            observer = contentObserver
            applicationContext.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                contentObserver,
            )
        }
    }

    fun exportCsv(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            writeCsv(context, output)
        } ?: throw IOException("export stream unavailable")
    }

    fun shareCsv(context: Context): Uri {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val file = File(directory, "calls-$stamp.csv")
        file.outputStream().use { output -> writeCsv(context, output) }
        directory.listFiles()?.forEach { older ->
            if (older != file) older.delete()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun writeCsv(context: Context, output: java.io.OutputStream) {
        val calls = load(context).calls
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        output.writer(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write("Name,Number,Type,Time,DurationSeconds,RecordId\n")
            calls.forEach { entry ->
                val time = formatter.format(Instant.ofEpochMilli(entry.timeMillis))
                writer.write(
                    listOf(
                        csv(entry.name),
                        csv(entry.number, asText = true),
                        csv(entry.typeLabel()),
                        csv(time),
                        entry.durationSec.toString(),
                        entry.id.toString(),
                    ).joinToString(","),
                )
                writer.write("\n")
            }
        }
    }

    private fun snapshotCallLog() {
        val context = appContext ?: return
        try {
            sync(context)
        } catch (error: Exception) {
            Log.w(tag, "observer sync failed: ${error.javaClass.simpleName}")
        }
    }

    private fun queryRecent(context: Context, savedAtMillis: Long): List<CallEntry> {
        val since = if (savedAtMillis > 0L) savedAtMillis - overlapMillis else 0L
        return try {
            queryCallLog(context, since, formatted = true)
        } catch (_: IllegalArgumentException) {
            try {
                queryCallLog(context, since, formatted = false)
            } catch (_: IllegalArgumentException) {
                queryCallLog(context, since = 0L, formatted = false)
            }
        }
    }

    private fun queryCallLog(context: Context, since: Long, formatted: Boolean): List<CallEntry> {
        val projection = mutableListOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        if (formatted) projection.add(2, CallLog.Calls.CACHED_FORMATTED_NUMBER)
        val selection = if (since > 0L) "${CallLog.Calls.DATE} >= ?" else null
        val args = if (since > 0L) arrayOf(since.toString()) else null
        val entries = ArrayList<CallEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection.toTypedArray(),
            selection,
            args,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val formattedIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_FORMATTED_NUMBER)
            val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            while (cursor.moveToNext() && entries.size < maxStored) {
                val time = longAt(cursor, dateIndex)
                if (time <= 0L) continue
                val raw = stringAt(cursor, numberIndex)
                val pretty = stringAt(cursor, formattedIndex)
                entries.add(
                    CallEntry(
                        id = longAt(cursor, idIndex),
                        number = pretty.ifBlank { raw }.trim(),
                        name = stringAt(cursor, nameIndex).trim(),
                        timeMillis = time,
                        durationSec = longAt(cursor, durationIndex)
                            .coerceAtLeast(0L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt(),
                        type = intAt(cursor, typeIndex),
                    ),
                )
            }
        }
        return entries
    }

    private fun loadUnlocked(context: Context): CallArchive {
        val directory = context.filesDir
        val candidates = listOf(
            File(directory, fileName),
            File(directory, "$fileName.tmp"),
            File(directory, "$fileName.bak"),
        )
        for (candidate in candidates) {
            readArchive(candidate)?.let { return it }
        }
        return CallArchive(emptyList(), 0L)
    }

    private fun saveUnlocked(context: Context, archive: CallArchive) {
        val directory = context.filesDir
        val file = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp")
        val backup = File(directory, "$fileName.bak")
        writeSynced(temporary, encode(archive))
        if (file.exists() && readArchive(file) == null && !file.delete()) {
            throw IOException("Could not delete the damaged archive file.")
        }
        if (file.exists()) {
            if (backup.exists() && !backup.delete()) {
                throw IOException("Could not delete the previous archive file.")
            }
            if (!file.renameTo(backup)) {
                throw IOException("Could not replace the archive file.")
            }
        }
        if (!temporary.renameTo(file)) {
            if (!file.exists() && backup.exists()) backup.renameTo(file)
            throw IOException("Could not save the archive file.")
        }
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun readArchive(file: File): CallArchive? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            decode(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(archive: CallArchive): String {
        val calls = JSONArray()
        archive.calls.forEach { entry ->
            calls.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("number", entry.number)
                    .put("name", entry.name)
                    .put("timeMillis", entry.timeMillis)
                    .put("durationSec", entry.durationSec)
                    .put("type", entry.type),
            )
        }
        return JSONObject()
            .put("savedAt", archive.savedAtMillis)
            .put("calls", calls)
            .toString()
    }

    private fun decode(text: String): CallArchive {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            return CallArchive(decodeCalls(JSONArray(trimmed)), 0L)
        }
        val root = JSONObject(trimmed)
        return CallArchive(
            calls = decodeCalls(root.optJSONArray("calls") ?: JSONArray()),
            savedAtMillis = root.optLong("savedAt"),
        )
    }

    private fun decodeCalls(array: JSONArray): List<CallEntry> {
        val map = LinkedHashMap<String, CallEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val time = item.optLong("timeMillis")
            if (time <= 0L) continue
            val entry = CallEntry(
                id = item.optLong("id"),
                number = item.optString("number"),
                name = item.optString("name"),
                timeMillis = time,
                durationSec = item.optInt("durationSec").coerceAtLeast(0),
                type = item.optInt("type"),
            )
            map.putIfAbsent(entry.archiveKey, entry)
        }
        return map.values.toList()
    }

    private fun notifyChanged() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (error: Exception) {
                Log.w(tag, "listener failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun stringAt(cursor: Cursor, index: Int): String {
        if (index < 0 || cursor.isNull(index)) return ""
        return cursor.getString(index).orEmpty()
    }

    private fun longAt(cursor: Cursor, index: Int): Long {
        if (index < 0 || cursor.isNull(index)) return 0L
        return cursor.getLong(index)
    }

    private fun intAt(cursor: Cursor, index: Int): Int {
        if (index < 0 || cursor.isNull(index)) return 0
        return cursor.getInt(index)
    }

    private fun csv(value: String, asText: Boolean = false): String {
        val cleaned = value.replace('\r', ' ').replace('\n', ' ').trim()
        val safe = if (asText && cleaned.isNotEmpty()) {
            "'$cleaned"
        } else if (cleaned.firstOrNull() in setOf('=', '+', '-', '@')) {
            "'$cleaned"
        } else {
            cleaned
        }
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
