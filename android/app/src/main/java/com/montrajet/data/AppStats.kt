package com.montrajet.data

data class AppStats(
    val totalManualSurveillances: Int = 0,
    val surveillancesFromApp: Int = 0,
    val surveillancesFromWidget: Int = 0,
    val totalScheduledChecks: Int = 0,
    val totalCancellations: Int = 0,
    val totalDelays: Int = 0,
    val totalAlertsSent: Int = 0,
    val totalChecks: Int = 0,
    val primCallsTotal: Int = 0,
    val primCalls2xx: Int = 0,
    val primCalls4xx: Int = 0,
    val primCalls5xx: Int = 0,
    val lastPrimStatus: Int? = null,
    val lastPrimCall: String? = null,
    val lastManualTrigger: String? = null,
    val lastScheduledCheck: String? = null,
    val lastUpdated: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): AppStats {
            return AppStats(
                totalManualSurveillances = (map["total_manual_surveillances"] as? Number)?.toInt() ?: 0,
                surveillancesFromApp = (map["surveillances_from_app"] as? Number)?.toInt() ?: 0,
                surveillancesFromWidget = (map["surveillances_from_widget"] as? Number)?.toInt() ?: 0,
                totalScheduledChecks = (map["total_scheduled_checks"] as? Number)?.toInt() ?: 0,
                totalCancellations = (map["total_cancellations"] as? Number)?.toInt() ?: 0,
                totalDelays = (map["total_delays"] as? Number)?.toInt() ?: 0,
                totalAlertsSent = (map["total_alerts_sent"] as? Number)?.toInt() ?: 0,
                totalChecks = (map["total_checks"] as? Number)?.toInt() ?: 0,
                primCallsTotal = (map["prim_calls_total"] as? Number)?.toInt() ?: 0,
                primCalls2xx = (map["prim_calls_2xx"] as? Number)?.toInt() ?: 0,
                primCalls4xx = (map["prim_calls_4xx"] as? Number)?.toInt() ?: 0,
                primCalls5xx = (map["prim_calls_5xx"] as? Number)?.toInt() ?: 0,
                lastPrimStatus = (map["last_prim_status"] as? Number)?.toInt(),
                lastPrimCall = map["last_prim_call"] as? String,
                lastManualTrigger = map["last_manual_trigger"] as? String,
                lastScheduledCheck = map["last_scheduled_check"] as? String,
                lastUpdated = map["last_updated"] as? String
            )
        }
    }
}
