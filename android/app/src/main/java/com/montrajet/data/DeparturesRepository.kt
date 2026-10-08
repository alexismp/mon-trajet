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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DeparturesRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _departures = MutableStateFlow<List<TrainDeparture>>(emptyList())
    val departures: StateFlow<List<TrainDeparture>> = _departures.asStateFlow()

    private val _lastUpdated = MutableStateFlow<String?>("Chargement...")
    val lastUpdated: StateFlow<String?> = _lastUpdated.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var firestoreListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        loadCachedDepartures()
        initFirestoreSync()
        refresh()
    }

    private fun loadCachedDepartures() {
        val jsonStr = prefs.getString(KEY_DEPARTURES_JSON, null)
        val updatedStr = prefs.getString(KEY_LAST_UPDATED, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<TrainDeparture>()
                for (i in 0 until array.length()) {
                    list.add(TrainDeparture.fromJsonObject(array.getJSONObject(i)))
                }
                _departures.value = list
                _lastUpdated.value = updatedStr ?: "Mis en cache"
                Log.d(TAG, "${list.size} départs chargés depuis le cache local.")
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lecture cache départs: ${e.message}")
            }
        }
    }

    private fun saveCachedDepartures(list: List<TrainDeparture>, updatedTime: String?) {
        try {
            val array = JSONArray()
            list.forEach { array.put(it.toJsonObject()) }
            prefs.edit().apply {
                putString(KEY_DEPARTURES_JSON, array.toString())
                putString(KEY_LAST_UPDATED, updatedTime)
                apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur écriture cache départs: ${e.message}")
        }
    }

    private fun initFirestoreSync() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection(COLLECTION_LIVE_STATUS).document(DOC_DEPARTURES)

            firestoreListener = docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Erreur écoute départs temps réel Firestore: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val rawDepartures = snapshot.get("departures") as? List<*>
                    val parsed = rawDepartures?.mapNotNull { item ->
                        (item as? Map<String, Any?>)?.let { TrainDeparture.fromMap(it) }
                    } ?: emptyList()

                    if (parsed.isNotEmpty()) {
                        val updated = formatIsoToTime(snapshot.getString("updated_at"))
                        _departures.value = parsed
                        _lastUpdated.value = updated
                        saveCachedDepartures(parsed, updated)
                        Log.d(TAG, "${parsed.size} départs synchronisés depuis Firestore.")
                        notifyWidgetUpdate()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore non disponible pour départs temps réel: ${e.message}")
        }
    }

    private fun isDepartureRecentOrUpcoming(departure: TrainDeparture): Boolean {
        val timeParts = departure.aimedTime.split(":")
        if (timeParts.size != 2) return true
        val hour = timeParts[0].trim().toIntOrNull() ?: return true
        val min = timeParts[1].trim().toIntOrNull() ?: return true

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Paris"))
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val depMinutes = hour * 60 + min

        // Si le train est passé depuis plus de 15 minutes, il n'est plus pertinent pour le widget
        val diff = depMinutes - nowMinutes
        return diff >= -15
    }

    fun hasDisruptions(minDelayMinutes: Int = 5): Boolean {
        return _departures.value.any {
            isDepartureRecentOrUpcoming(it) && (
                it.isCancelled || it.status == DepartureStatus.CANCELLED ||
                it.delayMinutes >= minDelayMinutes || (it.status == DepartureStatus.DELAYED && it.delayMinutes >= minDelayMinutes)
            )
        }
    }

    fun getDisruptionSummary(minDelayMinutes: Int = 5): String? {
        val validDeps = _departures.value.filter { isDepartureRecentOrUpcoming(it) }
        val cancelled = validDeps.filter { it.isCancelled || it.status == DepartureStatus.CANCELLED }
        if (cancelled.isNotEmpty()) {
            val t = cancelled.first()
            return "Train ${t.missionCode} ${t.aimedTime} supprimé"
        }
        val delayed = validDeps.filter { !it.isCancelled && it.delayMinutes >= minDelayMinutes }
        if (delayed.isNotEmpty()) {
            val t = delayed.first()
            return "Train ${t.missionCode} ${t.aimedTime} retardé (+${t.delayMinutes}m)"
        }
        return null
    }

    private var lastFetchTimestamp: Long = 0

    suspend fun refreshSuspended(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFetchTimestamp < CLIENT_CACHE_TTL_MS) && _departures.value.isNotEmpty()) {
            Log.d(TAG, "Consultation ponctuelle : utilisation du cache local client (< ${CLIENT_CACHE_TTL_MS / 1000}s), aucun appel réseau émis.")
            return@withContext true
        }

        _isLoading.value = true
        return@withContext try {
            val success = fetchFromBackend(force)
            if (success) {
                lastFetchTimestamp = System.currentTimeMillis()
                true
            } else {
                fetchFromFirestore()
                false
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun refresh(force: Boolean = false) {
        scope.launch {
            refreshSuspended(force)
        }
    }

    private suspend fun fetchFromBackend(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBackendDeparturesUrl()
            if (baseUrl.isBlank()) {
                Log.d(TAG, "BACKEND_URL non configuré, repli vers Firestore.")
                return@withContext false
            }

            val endpointUrl = if (force) "$baseUrl?fresh=true" else baseUrl
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 8000
                // 1. Clé secrète d'application (si définie dans local.properties)
                if (com.montrajet.BuildConfig.BACKEND_API_KEY.isNotBlank()) {
                    setRequestProperty("X-API-Key", com.montrajet.BuildConfig.BACKEND_API_KEY)
                }
            }

            // 2. Token Firebase App Check (attestation Play Integrity si disponible)
            try {
                val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
                val tokenResult = appCheck.getAppCheckToken(false).await()
                if (tokenResult != null && tokenResult.token.isNotBlank()) {
                    conn.setRequestProperty("X-Firebase-AppCheck", tokenResult.token)
                }
            } catch (e: Exception) {
                // App Check optionnel si non encore déployé
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.readText()
                reader.close()

                val json = JSONObject(responseText)
                val rawDeps = json.optJSONArray("departures")
                if (rawDeps != null) {
                    val list = mutableListOf<TrainDeparture>()
                    for (i in 0 until rawDeps.length()) {
                        val itemObj = rawDeps.getJSONObject(i)
                        list.add(TrainDeparture.fromJsonObject(itemObj))
                    }
                    if (list.isNotEmpty()) {
                        val updated = formatIsoToTime(json.optString("updated_at"))
                        _departures.value = list
                        _lastUpdated.value = updated
                        saveCachedDepartures(list, updated)
                        notifyWidgetUpdate()
                        val isCachedOnServer = json.optBoolean("cached", false)
                        val cacheAge = json.optInt("cache_age_seconds", 0)
                        Log.d(
                            TAG,
                            "${list.size} départs rafraîchis via backend (serveur en cache: $isCachedOnServer, âge: ${cacheAge}s)."
                        )
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec fetch backend HTTP: ${e.message}")
        }
        return@withContext false
    }

    private fun fetchFromFirestore() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection(COLLECTION_LIVE_STATUS).document(DOC_DEPARTURES).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        val rawDepartures = snapshot.get("departures") as? List<*>
                        val parsed = rawDepartures?.mapNotNull { item ->
                            (item as? Map<String, Any?>)?.let { TrainDeparture.fromMap(it) }
                        } ?: emptyList()
                        if (parsed.isNotEmpty()) {
                            val updated = formatIsoToTime(snapshot.getString("updated_at"))
                            _departures.value = parsed
                            _lastUpdated.value = updated
                            saveCachedDepartures(parsed, updated)
                            notifyWidgetUpdate()
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur fetch Firestore: ${e.message}")
        }
    }

    private fun notifyWidgetUpdate() {
        try {
            com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(context)
        } catch (e: Exception) {
            context.sendBroadcast(Intent("com.montrajet.widget.ACTION_REFRESH").setPackage(context.packageName))
        }
    }

    private fun formatIsoToTime(isoStr: String?): String {
        if (isoStr.isNullOrBlank()) {
            val nowFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
            return nowFormat.format(Date())
        }
        return try {
            val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = isoParser.parse(isoStr.substring(0, 19))
            val localFormat = SimpleDateFormat("HH:mm", Locale.FRANCE).apply {
                timeZone = TimeZone.getTimeZone("Europe/Paris")
            }
            localFormat.format(date ?: Date())
        } catch (e: Exception) {
            isoStr.takeLast(8).take(5)
        }
    }

    companion object {
        private const val TAG = "DeparturesRepository"
        private const val PREFS_NAME = "montrajet_departures_cache"
        private const val KEY_DEPARTURES_JSON = "departures_json"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val COLLECTION_LIVE_STATUS = "live_status"
        private const val DOC_DEPARTURES = "departures_meudon"
        private const val CLIENT_CACHE_TTL_MS = 60_000L // 60 secondes de cache local

        private fun getBackendDeparturesUrl(): String {
            val base = com.montrajet.BuildConfig.BACKEND_URL.trim().trimEnd('/')
            return if (base.isNotBlank()) "$base/departures" else ""
        }

        @Volatile
        private var instance: DeparturesRepository? = null

        fun getInstance(context: Context): DeparturesRepository {
            return instance ?: synchronized(this) {
                instance ?: DeparturesRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
