package com.katavu.usage.upload

import org.json.JSONArray
import org.json.JSONObject

data class KatavuUser(
    val name: String, val username: String, val role: String?, val organization: String?,
    val isActive: Boolean, val signedIn: Boolean, val lastLoginAt: String?,
)

data class KatavuLogin(
    val name: String, val role: String?, val organization: String?, val action: String, val at: String,
)

data class KatavuActivity(
    val user: String?, val action: String, val entity: String, val organization: String?, val at: String,
)

/** Mirror of the server's /api/katavu/usage response (KATAVU users and their activity). */
data class KatavuUsage(
    val totalUsers: Int,
    val activeUsers: Int,
    val signedIn: Int,
    val loginsToday: Int,
    val logins7d: Int,
    val organizations: Int,
    val users: List<KatavuUser>,
    val recentLogins: List<KatavuLogin>,
    val recentActivity: List<KatavuActivity>,
) {
    companion object {
        fun fromJson(json: String): KatavuUsage {
            val o = JSONObject(json)
            return KatavuUsage(
                totalUsers = o.getInt("totalUsers"),
                activeUsers = o.getInt("activeUsers"),
                signedIn = o.getInt("signedIn"),
                loginsToday = o.getInt("loginsToday"),
                logins7d = o.getInt("logins7d"),
                organizations = o.getInt("organizations"),
                users = o.optJSONArray("users").map {
                    KatavuUser(it.getString("name"), it.getString("username"), it.optStr("role"),
                        it.optStr("organization"), it.getBoolean("isActive"), it.getBoolean("signedIn"),
                        it.optStr("lastLoginAt"))
                },
                recentLogins = o.optJSONArray("recentLogins").map {
                    KatavuLogin(it.getString("name"), it.optStr("role"), it.optStr("organization"),
                        it.getString("action"), it.getString("at"))
                },
                recentActivity = o.optJSONArray("recentActivity").map {
                    KatavuActivity(it.optStr("user"), it.getString("action"), it.getString("entity"),
                        it.optStr("organization"), it.getString("at"))
                },
            )
        }

        private fun <T> JSONArray?.map(f: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

        private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else optString(key, null)
    }
}
