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
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class AlertRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("montrajet_alerts", Context.MODE_PRIVATE)

    private val _alerts = MutableStateFlow<List<TrainAlert>>(emptyList())
    val alerts: StateFlow<List<TrainAlert>> = _alerts.asStateFlow()

    init {
        loadAlertsFromPrefs()
    }

    @Synchronized
    fun addAlert(alert: TrainAlert) {
        val current = _alerts.value.toMutableList()
        // Évite les doublons stricts par ID
        if (current.none { it.id == alert.id }) {
            current.add(0, alert) // Dernier reçu en premier
            _alerts.value = current
            saveAlertsToPrefs(current)
        }
    }

    @Synchronized
    fun clearAlerts() {
        _alerts.value = emptyList()
        prefs.edit().remove("alerts_json").apply()
    }

    fun hasRecentAlerts(maxAgeMinutes: Long = 90): Boolean {
        val cutoff = System.currentTimeMillis() - (maxAgeMinutes * 60 * 1000)
        return _alerts.value.any { it.receivedAtTimestamp >= cutoff }
    }

    fun getLatestAlertSummary(): String? {
        val latest = _alerts.value.firstOrNull() ?: return null
        val prefix = if (latest.status.equals("RETARDÉ", ignoreCase = true)) "Retard" else "Suppression"
        return "$prefix : ${latest.missionCode} (${latest.departureTime})"
    }

    private fun loadAlertsFromPrefs() {
        val jsonStr = prefs.getString("alerts_json", null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<TrainAlert>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    TrainAlert(
                        id = obj.getString("id"),
                        missionCode = obj.optString("missionCode", "Ligne N"),
                        departureTime = obj.optString("departureTime", "--:--"),
                        stopName = obj.optString("stopName", "Meudon"),
                        destination = obj.optString("destination", "Paris-Montparnasse"),
                        status = obj.optString("status", "ANNULÉ"),
                        receivedAtTimestamp = obj.optLong("receivedAtTimestamp", System.currentTimeMillis())
                    )
                )
            }
            _alerts.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveAlertsToPrefs(list: List<TrainAlert>) {
        try {
            val jsonArray = JSONArray()
            // Conserver au maximum les 30 dernières alertes
            list.take(30).forEach { alert ->
                val obj = JSONObject().apply {
                    put("id", alert.id)
                    put("missionCode", alert.missionCode)
                    put("departureTime", alert.departureTime)
                    put("stopName", alert.stopName)
                    put("destination", alert.destination)
                    put("status", alert.status)
                    put("receivedAtTimestamp", alert.receivedAtTimestamp)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("alerts_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var instance: AlertRepository? = null

        fun getInstance(context: Context): AlertRepository {
            return instance ?: synchronized(this) {
                instance ?: AlertRepository(context).also { instance = it }
            }
        }
    }
}
