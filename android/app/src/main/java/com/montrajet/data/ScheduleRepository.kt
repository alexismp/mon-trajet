/*
 * Copyright 2026 Alexis Moussine-Pouchkine
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.montrajet.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ScheduleRepository private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _schedule = MutableStateFlow(loadLocalConfig())
    val schedule: StateFlow<ScheduleConfig> = _schedule.asStateFlow()

    private var firestoreListener: ListenerRegistration? = null

    init {
        initFirestoreSync()
    }

    private fun initFirestoreSync() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection(SETTINGS_COLLECTION).document(SCHEDULE_DOC)

            firestoreListener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Erreur écoute Firestore pour schedule: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val data = snapshot.data
                    if (data != null) {
                        val remoteConfig = ScheduleConfig.fromMap(data)
                        _schedule.value = remoteConfig
                        saveLocalConfig(remoteConfig)
                        notifyWidgetUpdate()
                        Log.d(TAG, "Configuration synchronisée depuis Firestore: $remoteConfig")
                    }
                } else if (snapshot != null && !snapshot.exists()) {
                    val current = _schedule.value
                    docRef.set(current.toMap(), SetOptions.merge())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore non initialisé (mode local actif): ${e.message}")
        }
    }

    fun saveConfig(newConfig: ScheduleConfig) {
        _schedule.value = newConfig
        saveLocalConfig(newConfig)
        notifyWidgetUpdate()

        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection(SETTINGS_COLLECTION).document(SCHEDULE_DOC)
                .set(newConfig.toMap(), SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Configuration mise à jour dans Firestore avec succès.")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Échec mise à jour Firestore: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'écrire dans Firestore: ${e.message}")
        }
    }

    fun toggleEnabled() {
        val current = _schedule.value
        saveConfig(current.copy(enabled = !current.enabled))
    }

    fun pauseForHours(hours: Int) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.HOUR_OF_DAY, hours)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val pauseIso = isoFormat.format(cal.time)
        val current = _schedule.value
        saveConfig(current.copy(pausedUntil = pauseIso))
    }

    fun pauseForToday() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris")).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val pauseIso = isoFormat.format(cal.time)
        val current = _schedule.value
        saveConfig(current.copy(pausedUntil = pauseIso))
    }

    /**
     * Met en pause la surveillance jusqu'au début du PROCHAIN trajet prévu :
     * - Fin du trajet matin -> pause jusqu'au soir (17h00)
     * - Fin du trajet soir -> pause jusqu'au lendemain matin (07h00)
     * Coupe les appels API et les notifications côté backend et mobile.
     */
    fun pauseUntilNextCommute(source: String = "manual") {
        val nextIso = _schedule.value.getNextCommuteStartIso()
        val current = _schedule.value
        saveConfig(
            current.copy(
                pausedUntil = nextIso,
                quickMonitoringUntil = null,
                returnCommuteUntil = null
            )
        )
        Log.i(TAG, "Surveillance mise en pause (source: $source) jusqu'au prochain trajet ($nextIso).")
    }

    fun toggleAutoStopAtDestination(enabled: Boolean) {
        val current = _schedule.value
        saveConfig(current.copy(autoStopAtDestination = enabled))
    }

    fun resumeNow() {
        val current = _schedule.value
        saveConfig(current.copy(pausedUntil = null, enabled = true))
    }

    /**
     * Déclenche une fenêtre de surveillance ponctuelle (Widget ou In-App).
     * @param durationMinutes Durée en minutes (défaut 60 min, paramétrable).
     * @param direction "TO_PARIS", "TO_MEUDON" ou "AUTO".
     * @param source "app" ou "widget".
     */
    fun triggerQuickMonitoring(durationMinutes: Int = 60, direction: String = "AUTO", source: String = "app") {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.MINUTE, durationMinutes)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val untilIso = isoFormat.format(cal.time)
        val current = _schedule.value
        saveConfig(
            current.copy(
                quickMonitoringUntil = untilIso,
                quickMonitoringDirection = direction,
                quickMonitoringDurationMinutes = durationMinutes,
                returnCommuteUntil = untilIso,
                enabled = true
            )
        )
        // Enregistrement des statistiques
        StatsRepository.getInstance(appContext).recordManualSurveillance(source, direction, durationMinutes)
        Log.i(TAG, "Surveillance ponctuelle activée ($direction, source: $source) pour ${durationMinutes}m jusqu'à $untilIso")
    }

    /**
     * Annule la surveillance ponctuelle.
     */
    fun cancelQuickMonitoring() {
        val current = _schedule.value
        saveConfig(
            current.copy(
                quickMonitoringUntil = null,
                returnCommuteUntil = null
            )
        )
        Log.i(TAG, "Surveillance ponctuelle annulée.")
    }

    fun triggerReturnCommute(hours: Int = 1) {
        triggerQuickMonitoring(durationMinutes = hours * 60, direction = "TO_MEUDON")
    }

    fun cancelReturnCommute() {
        cancelQuickMonitoring()
    }

    fun updateQuickDuration(durationMinutes: Int) {
        val current = _schedule.value
        saveConfig(current.copy(quickMonitoringDurationMinutes = durationMinutes))
    }

    fun toggleNotifyDelays(enabled: Boolean) {
        val current = _schedule.value
        saveConfig(current.copy(notifyDelays = enabled))
    }

    private fun notifyWidgetUpdate() {
        try {
            com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(appContext)
        } catch (e: Exception) {
            // ignore
        }
        try {
            val intent = Intent(ACTION_WIDGET_REFRESH).apply {
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur broadcast widget: ${e.message}")
        }
    }

    private fun loadLocalConfig(): ScheduleConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)

        val mEnabled = prefs.getBoolean(KEY_MORNING_ENABLED, true)
        val mStartHour = prefs.getInt(KEY_MORNING_START_HOUR, prefs.getInt(KEY_START_HOUR, 7))
        val mStartMinute = prefs.getInt(KEY_MORNING_START_MINUTE, prefs.getInt(KEY_START_MINUTE, 0))
        val mEndHour = prefs.getInt(KEY_MORNING_END_HOUR, prefs.getInt(KEY_END_HOUR, 9))
        val mEndMinute = prefs.getInt(KEY_MORNING_END_MINUTE, prefs.getInt(KEY_END_MINUTE, 30))

        val eEnabled = prefs.getBoolean(KEY_EVENING_ENABLED, true)
        val eStartHour = prefs.getInt(KEY_EVENING_START_HOUR, 17)
        val eStartMinute = prefs.getInt(KEY_EVENING_START_MINUTE, 0)
        val eEndHour = prefs.getInt(KEY_EVENING_END_HOUR, 19)
        val eEndMinute = prefs.getInt(KEY_EVENING_END_MINUTE, 30)

        val freq = prefs.getInt(KEY_FREQ, 3)
        val pausedUntil = prefs.getString(KEY_PAUSED_UNTIL, null)
        val returnCommuteUntil = prefs.getString(KEY_RETURN_COMMUTE_UNTIL, null)
        val quickMonitoringUntil = prefs.getString(KEY_QUICK_MONITORING_UNTIL, null)
        val quickMonitoringDirection = prefs.getString(KEY_QUICK_MONITORING_DIRECTION, null)
        val quickDuration = prefs.getInt(KEY_QUICK_MONITORING_DURATION, 60)
        val notifyDelays = prefs.getBoolean(KEY_NOTIFY_DELAYS, true)
        val minDelay = prefs.getInt(KEY_MIN_DELAY_MINUTES, 5)
        val autoStopAtDestination = prefs.getBoolean(KEY_AUTO_STOP_AT_DESTINATION, true)

        val daysString = prefs.getString(KEY_ACTIVE_DAYS, "0,1,2,3,4") ?: "0,1,2,3,4"

        val days = daysString.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(0, 1, 2, 3, 4) }

        return ScheduleConfig(
            enabled = enabled,
            morningEnabled = mEnabled,
            morningStartHour = mStartHour,
            morningStartMinute = mStartMinute,
            morningEndHour = mEndHour,
            morningEndMinute = mEndMinute,
            eveningEnabled = eEnabled,
            eveningStartHour = eStartHour,
            eveningStartMinute = eStartMinute,
            eveningEndHour = eEndHour,
            eveningEndMinute = eEndMinute,
            activeDays = days,
            frequencyMinutes = freq,
            pausedUntil = pausedUntil,
            returnCommuteUntil = returnCommuteUntil,
            quickMonitoringUntil = quickMonitoringUntil,
            quickMonitoringDirection = quickMonitoringDirection,
            quickMonitoringDurationMinutes = quickDuration,
            notifyDelays = notifyDelays,
            minDelayMinutes = minDelay,
            autoStopAtDestination = autoStopAtDestination
        )
    }

    private fun saveLocalConfig(config: ScheduleConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putBoolean(KEY_MORNING_ENABLED, config.morningEnabled)
            putInt(KEY_MORNING_START_HOUR, config.morningStartHour)
            putInt(KEY_MORNING_START_MINUTE, config.morningStartMinute)
            putInt(KEY_MORNING_END_HOUR, config.morningEndHour)
            putInt(KEY_MORNING_END_MINUTE, config.morningEndMinute)
            putInt(KEY_START_HOUR, config.morningStartHour)
            putInt(KEY_START_MINUTE, config.morningStartMinute)
            putInt(KEY_END_HOUR, config.morningEndHour)
            putInt(KEY_END_MINUTE, config.morningEndMinute)

            putBoolean(KEY_EVENING_ENABLED, config.eveningEnabled)
            putInt(KEY_EVENING_START_HOUR, config.eveningStartHour)
            putInt(KEY_EVENING_START_MINUTE, config.eveningStartMinute)
            putInt(KEY_EVENING_END_HOUR, config.eveningEndHour)
            putInt(KEY_EVENING_END_MINUTE, config.eveningEndMinute)

            putInt(KEY_FREQ, config.frequencyMinutes)
            putString(KEY_PAUSED_UNTIL, config.pausedUntil)
            putString(KEY_RETURN_COMMUTE_UNTIL, config.returnCommuteUntil)
            putString(KEY_QUICK_MONITORING_UNTIL, config.quickMonitoringUntil)
            putString(KEY_QUICK_MONITORING_DIRECTION, config.quickMonitoringDirection)
            putInt(KEY_QUICK_MONITORING_DURATION, config.quickMonitoringDurationMinutes)
            putBoolean(KEY_NOTIFY_DELAYS, config.notifyDelays)
            putInt(KEY_MIN_DELAY_MINUTES, config.minDelayMinutes)
            putBoolean(KEY_AUTO_STOP_AT_DESTINATION, config.autoStopAtDestination)

            putString(KEY_ACTIVE_DAYS, config.activeDays.joinToString(","))
            apply()
        }
    }

    companion object {
        private const val TAG = "ScheduleRepository"
        private const val PREFS_NAME = "montrajet_schedule_prefs"
        private const val SETTINGS_COLLECTION = "settings"
        private const val SCHEDULE_DOC = "monitoring_schedule"
        const val ACTION_WIDGET_REFRESH = "com.montrajet.widget.ACTION_REFRESH"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_HOUR = "start_hour"
        private const val KEY_START_MINUTE = "start_minute"
        private const val KEY_END_HOUR = "end_hour"
        private const val KEY_END_MINUTE = "end_minute"

        private const val KEY_MORNING_ENABLED = "morning_enabled"
        private const val KEY_MORNING_START_HOUR = "morning_start_hour"
        private const val KEY_MORNING_START_MINUTE = "morning_start_minute"
        private const val KEY_MORNING_END_HOUR = "morning_end_hour"
        private const val KEY_MORNING_END_MINUTE = "morning_end_minute"

        private const val KEY_EVENING_ENABLED = "evening_enabled"
        private const val KEY_EVENING_START_HOUR = "evening_start_hour"
        private const val KEY_EVENING_START_MINUTE = "evening_start_minute"
        private const val KEY_EVENING_END_HOUR = "evening_end_hour"
        private const val KEY_EVENING_END_MINUTE = "evening_end_minute"

        private const val KEY_FREQ = "frequency"
        private const val KEY_PAUSED_UNTIL = "paused_until"
        private const val KEY_RETURN_COMMUTE_UNTIL = "return_commute_until"
        private const val KEY_QUICK_MONITORING_UNTIL = "quick_monitoring_until"
        private const val KEY_QUICK_MONITORING_DIRECTION = "quick_monitoring_direction"
        private const val KEY_QUICK_MONITORING_DURATION = "quick_monitoring_duration"
        private const val KEY_NOTIFY_DELAYS = "notify_delays"
        private const val KEY_MIN_DELAY_MINUTES = "min_delay_minutes"
        private const val KEY_AUTO_STOP_AT_DESTINATION = "auto_stop_at_destination"
        private const val KEY_ACTIVE_DAYS = "active_days"

        @Volatile
        private var instance: ScheduleRepository? = null

        fun getInstance(context: Context): ScheduleRepository {
            return instance ?: synchronized(this) {
                instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
