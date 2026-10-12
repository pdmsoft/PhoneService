package com.phoneservice.call

import android.provider.CallLog

data class CallEntry(
    val number: String,
    val timeMillis: Long,
    val type: Int,
    val durationSec: Int = 0,
    val name: String = "",
    val id: Long = 0,
) {
    val archiveKey: String
        get() = "$timeMillis|${normalizedNumber(number)}|$type"

    fun typeLabel(): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "Answered on another device"
        else -> "Other"
    }

    fun durationLabel(): String {
        if (durationSec <= 0) return ""
        val hours = durationSec / 3600
        val minutes = (durationSec % 3600) / 60
        val seconds = durationSec % 60
        return listOfNotNull(
            if (hours > 0) "${hours}h" else null,
            if (minutes > 0) "${minutes}m" else null,
            if (seconds > 0 || (hours == 0 && minutes == 0)) "${seconds}s" else null,
        ).joinToString(" ")
    }
}

data class CallArchive(
    val calls: List<CallEntry>,
    val savedAtMillis: Long,
)

fun normalizedNumber(raw: String): String {
    val compact = buildString(raw.length) {
        raw.forEach { ch ->
            if (ch.isDigit() || ch == '+' || ch == '*' || ch == '#') append(ch)
        }
    }
    if (!compact.startsWith("+82")) return compact
    val national = compact.removePrefix("+82")
    if (national.isEmpty()) return compact
    return if (national.startsWith("0")) national else "0$national"
}

fun mergeCalls(fresh: List<CallEntry>, stored: List<CallEntry>, limit: Int): List<CallEntry> {
    val map = LinkedHashMap<String, CallEntry>()
    for (entry in stored) {
        if (entry.timeMillis > 0L) map[entry.archiveKey] = entry
    }
    for (entry in fresh) {
        if (entry.timeMillis <= 0L) continue
        val kept = map[entry.archiveKey]
        map[entry.archiveKey] = if (kept == null) entry else kept.withNewerDetails(entry)
    }
    return map.values.sortedByDescending { it.timeMillis }.take(limit)
}

private fun CallEntry.withNewerDetails(newer: CallEntry): CallEntry = copy(
    number = newer.number.ifBlank { number },
    name = newer.name.ifBlank { name },
    durationSec = maxOf(durationSec, newer.durationSec),
    id = if (newer.id != 0L) newer.id else id,
)
