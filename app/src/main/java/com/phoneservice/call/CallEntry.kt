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
        CallLog.Calls.INCOMING_TYPE -> "수신"
        CallLog.Calls.OUTGOING_TYPE -> "발신"
        CallLog.Calls.MISSED_TYPE -> "부재중"
        CallLog.Calls.VOICEMAIL_TYPE -> "음성사서함"
        CallLog.Calls.REJECTED_TYPE -> "거절"
        CallLog.Calls.BLOCKED_TYPE -> "차단"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "다른 기기에서 받음"
        else -> "기타"
    }

    fun durationLabel(): String {
        if (durationSec <= 0) return ""
        val hours = durationSec / 3600
        val minutes = (durationSec % 3600) / 60
        val seconds = durationSec % 60
        return listOfNotNull(
            if (hours > 0) "${hours}시간" else null,
            if (minutes > 0) "${minutes}분" else null,
            if (seconds > 0 || (hours == 0 && minutes == 0)) "${seconds}초" else null,
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
    for (entry in fresh) {
        if (entry.timeMillis > 0L) map.putIfAbsent(entry.archiveKey, entry)
    }
    for (entry in stored) {
        if (entry.timeMillis > 0L) map.putIfAbsent(entry.archiveKey, entry)
    }
    return map.values.sortedByDescending { it.timeMillis }.take(limit)
}
