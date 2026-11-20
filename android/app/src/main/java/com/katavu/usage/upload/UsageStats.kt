package com.katavu.usage.upload

import org.json.JSONObject

data class DayCount(val date: String, val count: Int, val durationSeconds: Long)
data class NumberCount(val phoneNumber: String, val count: Int, val durationSeconds: Long)

/** Mirror of the server's /api/stats response. */
data class UsageStats(
    val totalCount: Int,
    val totalDurationSeconds: Long,
    val totalBytes: Long,
    val incoming: Int,
    val outgoing: Int,
    val thisMonthCount: Int,
    val thisMonthDurationSeconds: Long,
    val perDay: List<DayCount>,
    val topNumbers: List<NumberCount>,
) {
    companion object {
        fun fromJson(json: String): UsageStats {
            val o = JSONObject(json)
            val perDay = o.optJSONArray("perDay")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { DayCount(it.getString("date"), it.getInt("count"), it.getLong("durationSeconds")) }
            } ?: emptyList()
            val top = o.optJSONArray("topNumbers")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { NumberCount(it.getString("phoneNumber"), it.getInt("count"), it.getLong("durationSeconds")) }
            } ?: emptyList()
            return UsageStats(
                totalCount = o.getInt("totalCount"),
                totalDurationSeconds = o.getLong("totalDurationSeconds"),
                totalBytes = o.getLong("totalBytes"),
                incoming = o.getInt("incoming"),
                outgoing = o.getInt("outgoing"),
                thisMonthCount = o.getInt("thisMonthCount"),
                thisMonthDurationSeconds = o.getLong("thisMonthDurationSeconds"),
                perDay = perDay,
                topNumbers = top,
            )
        }
    }
}
