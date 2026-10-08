package com.montrajet.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class StatsRepository private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _stats = MutableStateFlow(loadLocalStats())
    val stats: StateFlow<AppStats> = _stats.asStateFlow()

    private var firestoreListener: ListenerRegistration? = null

    init {
        initFirestoreSync()
    }

    private fun initFirestoreSync() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection(COLLECTION_STATS).document(DOC_SUMMARY)

            firestoreListener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Erreur écoute Firestore pour les statistiques: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val data = snapshot.data
                    if (data != null) {
                        val parsed = AppStats.fromMap(data)
                        _stats.value = parsed
                        saveLocalStats(parsed)
                        Log.d(TAG, "Statistiques synchronisées depuis Firestore: $parsed")
                    }
                } else if (snapshot != null && !snapshot.exists()) {
                    val current = _stats.value
                    val initialMap = mapOf(
                        "total_manual_surveillances" to current.totalManualSurveillances,
                        "surveillances_from_app" to current.surveillancesFromApp,
                        "surveillances_from_widget" to current.surveillancesFromWidget,
                        "total_scheduled_checks" to current.totalScheduledChecks,
                        "total_cancellations" to current.totalCancellations,
                        "total_delays" to current.totalDelays,
                        "total_alerts_sent" to current.totalAlertsSent,
                        "total_checks" to current.totalChecks
                    )
                    docRef.set(initialMap, SetOptions.merge())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore non disponible pour les statistiques: ${e.message}")
        }
    }

    /**
     * Enregistre un déclenchement manuel de surveillance (App ou Widget).
     */
    fun recordManualSurveillance(source: String = "app", direction: String = "AUTO", durationMinutes: Int = 60) {
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val current = _stats.value
        val updated = current.copy(
            totalManualSurveillances = current.totalManualSurveillances + 1,
            surveillancesFromApp = if (source != "widget") current.surveillancesFromApp + 1 else current.surveillancesFromApp,
            surveillancesFromWidget = if (source == "widget") current.surveillancesFromWidget + 1 else current.surveillancesFromWidget,
            lastManualTrigger = nowIso,
            lastUpdated = nowIso
        )
        _stats.value = updated
        saveLocalStats(updated)

        try {
            val firestore = FirebaseFirestore.getInstance()
            val updates = mutableMapOf<String, Any>(
                "total_manual_surveillances" to FieldValue.increment(1),
                "last_manual_trigger" to nowIso,
                "last_updated" to nowIso
            )
            if (source == "widget") {
                updates["surveillances_from_widget"] = FieldValue.increment(1)
            } else {
                updates["surveillances_from_app"] = FieldValue.increment(1)
            }

            firestore.collection(COLLECTION_STATS).document(DOC_SUMMARY)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Statistiques de déclenchement manuel enregistrées dans Firestore ($source).")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Échec mise à jour statistiques Firestore: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'écrire les statistiques dans Firestore: ${e.message}")
        }
    }

    fun refresh() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection(COLLECTION_STATS).document(DOC_SUMMARY).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        val data = snapshot.data
                        if (data != null) {
                            val parsed = AppStats.fromMap(data)
                            _stats.value = parsed
                            saveLocalStats(parsed)
                        }
                    }
                }
        } catch (ignored: Exception) {
        }
    }

    private fun loadLocalStats(): AppStats {
        return AppStats(
            totalManualSurveillances = prefs.getInt(KEY_MANUAL_SURVEILLANCES, 0),
            surveillancesFromApp = prefs.getInt(KEY_FROM_APP, 0),
            surveillancesFromWidget = prefs.getInt(KEY_FROM_WIDGET, 0),
            totalScheduledChecks = prefs.getInt(KEY_SCHEDULED_CHECKS, 0),
            totalCancellations = prefs.getInt(KEY_CANCELLATIONS, 0),
            totalDelays = prefs.getInt(KEY_DELAYS, 0),
            totalAlertsSent = prefs.getInt(KEY_ALERTS_SENT, 0),
            totalChecks = prefs.getInt(KEY_TOTAL_CHECKS, 0),
            primCallsTotal = prefs.getInt(KEY_PRIM_CALLS_TOTAL, 0),
            primCalls2xx = prefs.getInt(KEY_PRIM_CALLS_2XX, 0),
            primCalls4xx = prefs.getInt(KEY_PRIM_CALLS_4XX, 0),
            primCalls5xx = prefs.getInt(KEY_PRIM_CALLS_5XX, 0),
            lastPrimStatus = if (prefs.contains(KEY_LAST_PRIM_STATUS)) prefs.getInt(KEY_LAST_PRIM_STATUS, 0) else null,
            lastPrimCall = prefs.getString(KEY_LAST_PRIM_CALL, null),
            lastManualTrigger = prefs.getString(KEY_LAST_MANUAL, null),
            lastScheduledCheck = prefs.getString(KEY_LAST_SCHEDULED, null),
            lastUpdated = prefs.getString(KEY_LAST_UPDATED, null)
        )
    }

    private fun saveLocalStats(stats: AppStats) {
        prefs.edit().apply {
            putInt(KEY_MANUAL_SURVEILLANCES, stats.totalManualSurveillances)
            putInt(KEY_FROM_APP, stats.surveillancesFromApp)
            putInt(KEY_FROM_WIDGET, stats.surveillancesFromWidget)
            putInt(KEY_SCHEDULED_CHECKS, stats.totalScheduledChecks)
            putInt(KEY_CANCELLATIONS, stats.totalCancellations)
            putInt(KEY_DELAYS, stats.totalDelays)
            putInt(KEY_ALERTS_SENT, stats.totalAlertsSent)
            putInt(KEY_TOTAL_CHECKS, stats.totalChecks)
            putInt(KEY_PRIM_CALLS_TOTAL, stats.primCallsTotal)
            putInt(KEY_PRIM_CALLS_2XX, stats.primCalls2xx)
            putInt(KEY_PRIM_CALLS_4XX, stats.primCalls4xx)
            putInt(KEY_PRIM_CALLS_5XX, stats.primCalls5xx)
            if (stats.lastPrimStatus != null) {
                putInt(KEY_LAST_PRIM_STATUS, stats.lastPrimStatus)
            } else {
                remove(KEY_LAST_PRIM_STATUS)
            }
            putString(KEY_LAST_PRIM_CALL, stats.lastPrimCall)
            putString(KEY_LAST_MANUAL, stats.lastManualTrigger)
            putString(KEY_LAST_SCHEDULED, stats.lastScheduledCheck)
            putString(KEY_LAST_UPDATED, stats.lastUpdated)
            apply()
        }
    }

    companion object {
        private const val TAG = "StatsRepository"
        private const val PREFS_NAME = "montrajet_stats_prefs"
        private const val COLLECTION_STATS = "settings"
        private const val DOC_SUMMARY = "stats"

        private const val KEY_MANUAL_SURVEILLANCES = "stat_manual_surveillances"
        private const val KEY_FROM_APP = "stat_from_app"
        private const val KEY_FROM_WIDGET = "stat_from_widget"
        private const val KEY_SCHEDULED_CHECKS = "stat_scheduled_checks"
        private const val KEY_CANCELLATIONS = "stat_cancellations"
        private const val KEY_DELAYS = "stat_delays"
        private const val KEY_ALERTS_SENT = "stat_alerts_sent"
        private const val KEY_TOTAL_CHECKS = "stat_total_checks"
        private const val KEY_PRIM_CALLS_TOTAL = "stat_prim_calls_total"
        private const val KEY_PRIM_CALLS_2XX = "stat_prim_calls_2xx"
        private const val KEY_PRIM_CALLS_4XX = "stat_prim_calls_4xx"
        private const val KEY_PRIM_CALLS_5XX = "stat_prim_calls_5xx"
        private const val KEY_LAST_PRIM_STATUS = "stat_last_prim_status"
        private const val KEY_LAST_PRIM_CALL = "stat_last_prim_call"
        private const val KEY_LAST_MANUAL = "stat_last_manual"
        private const val KEY_LAST_SCHEDULED = "stat_last_scheduled"
        private const val KEY_LAST_UPDATED = "stat_last_updated"

        @Volatile
        private var instance: StatsRepository? = null

        fun getInstance(context: Context): StatsRepository {
            return instance ?: synchronized(this) {
                instance ?: StatsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
