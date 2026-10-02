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

package com.nulltrack.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ScheduleConfig(
    val enabled: Boolean = true,
    // Plage Matin : Meudon ➔ Paris-Montparnasse
    val morningEnabled: Boolean = true,
    val morningStartHour: Int = 7,
    val morningStartMinute: Int = 0,
    val morningEndHour: Int = 9,
    val morningEndMinute: Int = 30,
    // Plage Soir : Paris-Montparnasse ➔ Meudon
    val eveningEnabled: Boolean = true,
    val eveningStartHour: Int = 17,
    val eveningStartMinute: Int = 0,
    val eveningEndHour: Int = 19,
    val eveningEndMinute: Int = 30,
    // Jours actifs & Fréquence
    val activeDays: List<Int> = listOf(0, 1, 2, 3, 4), // 0=Lundi, 1=Mardi, ..., 6=Dimanche
    val frequencyMinutes: Int = 3,
    // Mise en pause temporaire (Snooze)
    val pausedUntil: String? = null, // Format ISO UTC "yyyy-MM-dd'T'HH:mm:ss'Z'"
    // Déclencheur ponctuel retour travail (rétrocompatibilité)
    val returnCommuteUntil: String? = null, // Format ISO UTC
    // Surveillance ponctuelle par Widget / Géolocalisation (durée paramétrable, défaut: 60 min)
    val quickMonitoringUntil: String? = null,
    val quickMonitoringDirection: String? = null, // "TO_PARIS", "TO_MEUDON" ou "AUTO"
    val quickMonitoringDurationMinutes: Int = 60,
    // Notification des retards en plus des annulations
    val notifyDelays: Boolean = true,
    val minDelayMinutes: Int = 5,
    // Arrêt automatique des alertes et requêtes à l'arrivée à destination (Géolocalisation)
    val autoStopAtDestination: Boolean = true
) {
    // Rétrocompatibilité
    val startHour: Int get() = morningStartHour
    val startMinute: Int get() = morningStartMinute
    val endHour: Int get() = morningEndHour
    val endMinute: Int get() = morningEndMinute

    fun isPaused(): Boolean {
        if (pausedUntil.isNullOrBlank()) return false
        val pauseDate = parseIsoDate(pausedUntil) ?: return false
        return Date().before(pauseDate)
    }

    fun getPausedRemainingText(): String? {
        if (!isPaused() || pausedUntil == null) return null
        val pauseDate = parseIsoDate(pausedUntil) ?: return null
        val parisFormat = SimpleDateFormat("HH:mm", Locale.FRANCE).apply {
            timeZone = TimeZone.getTimeZone("Europe/Paris")
        }
        return parisFormat.format(pauseDate)
    }

    /**
     * Indique si une surveillance ponctuelle (Widget ou Déclencheur retour) est actuellement en cours.
     */
    fun isQuickMonitoringActive(): Boolean {
        val target = quickMonitoringUntil ?: returnCommuteUntil
        if (target.isNullOrBlank()) return false
        val retDate = parseIsoDate(target) ?: return false
        return Date().before(retDate)
    }

    fun isReturnCommuteActive(): Boolean = isQuickMonitoringActive()

    /**
     * Indique si l'heure actuelle correspond à un créneau programmé (matin ou soir).
     */
    fun isWindowActiveNow(): Boolean {
        if (!enabled || isPaused()) return false

        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"))
        val dayOfWeek = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        if (dayOfWeek !in activeDays) return false

        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

        if (morningEnabled) {
            val mStart = morningStartHour * 60 + morningStartMinute
            val mEnd = morningEndHour * 60 + morningEndMinute
            if (currentMinutes in mStart..mEnd) return true
        }

        if (eveningEnabled) {
            val eStart = eveningStartHour * 60 + eveningStartMinute
            val eEnd = eveningEndHour * 60 + eveningEndMinute
            if (currentMinutes in eStart..eEnd) return true
        }

        return false
    }

    /**
     * Indique si la surveillance est active à cet instant précis (ponctuelle ou programmée).
     */
    fun isMonitoringActiveNow(): Boolean {
        if (!enabled || isPaused()) return false
        if (isQuickMonitoringActive()) return true
        return isWindowActiveNow()
    }

    /**
     * Retourne l'heure d'expiration de la surveillance ponctuelle (ex: 18:45).
     */
    fun getQuickMonitoringRemainingText(): String? {
        val target = quickMonitoringUntil ?: returnCommuteUntil
        if (!isQuickMonitoringActive() || target == null) return null
        val retDate = parseIsoDate(target) ?: return null
        val parisFormat = SimpleDateFormat("HH:mm", Locale.FRANCE).apply {
            timeZone = TimeZone.getTimeZone("Europe/Paris")
        }
        return parisFormat.format(retDate)
    }

    fun getReturnCommuteRemainingText(): String? = getQuickMonitoringRemainingText()

    fun getActiveDirectionCode(): String? {
        if (isQuickMonitoringActive() && quickMonitoringDirection != null) {
            return quickMonitoringDirection
        }
        if (isWindowActiveNow()) {
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"))
            val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val mStart = morningStartHour * 60 + morningStartMinute
            val mEnd = morningEndHour * 60 + morningEndMinute
            if (morningEnabled && currentMinutes in mStart..mEnd) {
                return "TO_PARIS"
            }
            val eStart = eveningStartHour * 60 + eveningStartMinute
            val eEnd = eveningEndHour * 60 + eveningEndMinute
            if (eveningEnabled && currentMinutes in eStart..eEnd) {
                return "TO_MEUDON"
            }
        }
        return null
    }

    fun getActiveRemainingText(): String? {
        if (isQuickMonitoringActive()) {
            return getQuickMonitoringRemainingText()
        }
        if (isWindowActiveNow()) {
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"))
            val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val mStart = morningStartHour * 60 + morningStartMinute
            val mEnd = morningEndHour * 60 + morningEndMinute
            if (morningEnabled && currentMinutes in mStart..mEnd) {
                return String.format(Locale.FRANCE, "%02dh%02d", morningEndHour, morningEndMinute)
            }
            val eStart = eveningStartHour * 60 + eveningStartMinute
            val eEnd = eveningEndHour * 60 + eveningEndMinute
            if (eveningEnabled && currentMinutes in eStart..eEnd) {
                return String.format(Locale.FRANCE, "%02dh%02d", eveningEndHour, eveningEndMinute)
            }
        }
        return null
    }

    /**
     * Calcule le prochain horodatage (en millisecondes Epoch) où le statut de surveillance change :
     * - Fin de la surveillance ponctuelle (Quick monitoring ou return commute)
     * - Fin de la mise en pause (Snooze)
     * - Début ou fin d'une plage horaire programmée (matin ou soir)
     *
     * Retourne null si aucune transition future n'est programmée.
     */
    fun getNextTransitionMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        val candidates = mutableListOf<Long>()
        val tz = TimeZone.getTimeZone("Europe/Paris")

        // 1. Fin de la surveillance ponctuelle (Quick monitoring ou retour)
        val quickTarget = quickMonitoringUntil ?: returnCommuteUntil
        if (!quickTarget.isNullOrBlank()) {
            val dt = parseIsoDate(quickTarget)
            if (dt != null && dt.time > nowMillis) {
                candidates.add(dt.time)
            }
        }

        // 2. Fin de la mise en pause temporaire (Snooze)
        if (!pausedUntil.isNullOrBlank()) {
            val pauseDate = parseIsoDate(pausedUntil)
            if (pauseDate != null && pauseDate.time > nowMillis) {
                candidates.add(pauseDate.time)
            }
        }

        // 3. Transitions des plages programmées (matin et soir) sur les 7 prochains jours
        if (enabled) {
            val baseCal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
            for (dayOffset in 0..7) {
                val checkCal = (baseCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                }
                // Convention activeDays : 0=Lundi, ..., 6=Dimanche
                val dayOfWeek = (checkCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                if (dayOfWeek in activeDays) {
                    // Plage Matin
                    if (morningEnabled) {
                        val mStart = (checkCal.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, morningStartHour)
                            set(Calendar.MINUTE, morningStartMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (mStart.timeInMillis > nowMillis) {
                            candidates.add(mStart.timeInMillis)
                        }

                        val mEnd = (checkCal.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, morningEndHour)
                            set(Calendar.MINUTE, morningEndMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (mEnd.timeInMillis > nowMillis) {
                            candidates.add(mEnd.timeInMillis)
                        }
                    }

                    // Plage Soir
                    if (eveningEnabled) {
                        val eStart = (checkCal.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, eveningStartHour)
                            set(Calendar.MINUTE, eveningStartMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (eStart.timeInMillis > nowMillis) {
                            candidates.add(eStart.timeInMillis)
                        }

                        val eEnd = (checkCal.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, eveningEndHour)
                            set(Calendar.MINUTE, eveningEndMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (eEnd.timeInMillis > nowMillis) {
                            candidates.add(eEnd.timeInMillis)
                        }
                    }
                }
            }
        }

        return candidates.filter { it > nowMillis }.minOrNull()
    }

    /**
     * Calcule l'horodatage exact (epoch millis) du début du PROCHAIN trajet prévu :
     * - Si on termine le trajet du matin (ou en journée avant le soir) -> début de la plage du soir (ex: 17h00).
     * - Si on termine le trajet du soir (ou après la plage du matin sans plage du soir) -> début du prochain jour actif (ex: 07h00 demain).
     */
    fun getNextCommuteStartMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val tz = TimeZone.getTimeZone("Europe/Paris")
        val nowCal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
        val currentMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val dayOfWeek = (nowCal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 0=Lundi, ..., 6=Dimanche

        // 1. Si aujourd'hui est un jour actif et qu'on est avant le début du soir
        if (dayOfWeek in activeDays && eveningEnabled) {
            val eStartMinutes = eveningStartHour * 60 + eveningStartMinute
            if (currentMinutes < eStartMinutes) {
                val todayEvening = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, eveningStartHour)
                    set(Calendar.MINUTE, eveningStartMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return todayEvening.timeInMillis
            }
        }

        // 2. Sinon, chercher le début du prochain jour actif (de demain à +7 jours)
        for (dayOffset in 1..7) {
            val checkCal = (nowCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val checkDayOfWeek = (checkCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (checkDayOfWeek in activeDays) {
                if (morningEnabled) {
                    val nextMorning = (checkCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, morningStartHour)
                        set(Calendar.MINUTE, morningStartMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return nextMorning.timeInMillis
                } else if (eveningEnabled) {
                    val nextEvening = (checkCal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, eveningStartHour)
                        set(Calendar.MINUTE, eveningStartMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return nextEvening.timeInMillis
                }
            }
        }

        // Par défaut si aucun créneau actif trouvé : pause de 8 heures
        return nowMillis + 8 * 3600 * 1000L
    }

    /**
     * Retourne la date ISO UTC de la reprise au prochain créneau (pour Firestore et paused_until).
     */
    fun getNextCommuteStartIso(nowMillis: Long = System.currentTimeMillis()): String {
        val targetMillis = getNextCommuteStartMillis(nowMillis)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return isoFormat.format(Date(targetMillis))
    }

    /**
     * Libellé lisible de la direction actuellement surveillée en mode ponctuel ou programmé.
     */
    fun getActiveDirectionText(): String {
        val code = getActiveDirectionCode()
        return when (code) {
            "TO_PARIS" -> "Meudon ➔ Paris"
            "TO_MEUDON" -> "Paris ➔ Meudon"
            else -> getQuickMonitoringDirectionText()
        }
    }

    /**
     * Libellé lisible de la direction actuellement surveillée en mode ponctuel.
     */
    fun getQuickMonitoringDirectionText(): String {
        return when (quickMonitoringDirection) {
            "TO_PARIS" -> "Meudon ➔ Paris"
            "TO_MEUDON" -> "Paris ➔ Meudon"
            else -> if (returnCommuteUntil != null) "Paris ➔ Meudon" else "Direction auto (Position)"
        }
    }

    fun formatMorningTimeRange(): String {
        return String.format(Locale.FRANCE, "%02dh%02d - %02dh%02d", morningStartHour, morningStartMinute, morningEndHour, morningEndMinute)
    }

    fun formatEveningTimeRange(): String {
        return String.format(Locale.FRANCE, "%02dh%02d - %02dh%02d", eveningStartHour, eveningStartMinute, eveningEndHour, eveningEndMinute)
    }

    fun formatTimeRange(): String = formatMorningTimeRange()

    fun formatActiveDays(): String {
        if (activeDays.isEmpty()) return "Aucun jour"
        if (activeDays.size == 7) return "Tous les jours"
        if (activeDays == listOf(0, 1, 2, 3, 4)) return "Lun - Ven"
        if (activeDays == listOf(5, 6)) return "Week-end"

        val dayLabels = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
        return activeDays.sorted().mapNotNull { index ->
            if (index in dayLabels.indices) dayLabels[index] else null
        }.joinToString(", ")
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "enabled" to enabled,
            "morning_enabled" to morningEnabled,
            "morning_start_hour" to morningStartHour,
            "morning_start_minute" to morningStartMinute,
            "morning_end_hour" to morningEndHour,
            "morning_end_minute" to morningEndMinute,
            "start_hour" to morningStartHour,
            "start_minute" to morningStartMinute,
            "end_hour" to morningEndHour,
            "end_minute" to morningEndMinute,
            "evening_enabled" to eveningEnabled,
            "evening_start_hour" to eveningStartHour,
            "evening_start_minute" to eveningStartMinute,
            "evening_end_hour" to eveningEndHour,
            "evening_end_minute" to eveningEndMinute,
            "active_days" to activeDays,
            "frequency_minutes" to frequencyMinutes,
            "paused_until" to pausedUntil,
            "return_commute_until" to returnCommuteUntil,
            "quick_monitoring_until" to quickMonitoringUntil,
            "quick_monitoring_direction" to quickMonitoringDirection,
            "quick_monitoring_duration_minutes" to quickMonitoringDurationMinutes,
            "notify_delays" to notifyDelays,
            "min_delay_minutes" to minDelayMinutes,
            "auto_stop_at_destination" to autoStopAtDestination
        )
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): ScheduleConfig {
            val enabled = (map["enabled"] as? Boolean) ?: true

            val mEnabled = (map["morning_enabled"] as? Boolean) ?: true
            val mStartH = (map["morning_start_hour"] as? Number)?.toInt()
                ?: (map["start_hour"] as? Number)?.toInt() ?: 7
            val mStartM = (map["morning_start_minute"] as? Number)?.toInt()
                ?: (map["start_minute"] as? Number)?.toInt() ?: 0
            val mEndH = (map["morning_end_hour"] as? Number)?.toInt()
                ?: (map["end_hour"] as? Number)?.toInt() ?: 9
            val mEndM = (map["morning_end_minute"] as? Number)?.toInt()
                ?: (map["end_minute"] as? Number)?.toInt() ?: 30

            val eEnabled = (map["evening_enabled"] as? Boolean) ?: true
            val eStartH = (map["evening_start_hour"] as? Number)?.toInt() ?: 17
            val eStartM = (map["evening_start_minute"] as? Number)?.toInt() ?: 0
            val eEndH = (map["evening_end_hour"] as? Number)?.toInt() ?: 19
            val eEndM = (map["evening_end_minute"] as? Number)?.toInt() ?: 30

            val frequencyMinutes = (map["frequency_minutes"] as? Number)?.toInt() ?: 3

            val rawDays = map["active_days"] as? List<*>
            val activeDays = rawDays?.mapNotNull { (it as? Number)?.toInt() } ?: listOf(0, 1, 2, 3, 4)

            val pausedUntil = map["paused_until"] as? String
            val returnCommuteUntil = map["return_commute_until"] as? String
            val quickMonitoringUntil = map["quick_monitoring_until"] as? String
            val quickMonitoringDirection = map["quick_monitoring_direction"] as? String
            val quickDuration = (map["quick_monitoring_duration_minutes"] as? Number)?.toInt() ?: 60
            val notifyDelays = (map["notify_delays"] as? Boolean) ?: true
            val minDelayMinutes = (map["min_delay_minutes"] as? Number)?.toInt() ?: 5
            val autoStopAtDestination = (map["auto_stop_at_destination"] as? Boolean) ?: true

            return ScheduleConfig(
                enabled = enabled,
                morningEnabled = mEnabled,
                morningStartHour = mStartH,
                morningStartMinute = mStartM,
                morningEndHour = mEndH,
                morningEndMinute = mEndM,
                eveningEnabled = eEnabled,
                eveningStartHour = eStartH,
                eveningStartMinute = eStartM,
                eveningEndHour = eEndH,
                eveningEndMinute = eEndM,
                activeDays = activeDays,
                frequencyMinutes = frequencyMinutes,
                pausedUntil = pausedUntil,
                returnCommuteUntil = returnCommuteUntil,
                quickMonitoringUntil = quickMonitoringUntil,
                quickMonitoringDirection = quickMonitoringDirection,
                quickMonitoringDurationMinutes = quickDuration,
                notifyDelays = notifyDelays,
                minDelayMinutes = minDelayMinutes,
                autoStopAtDestination = autoStopAtDestination
            )
        }

        private fun parseIsoDate(isoString: String): Date? {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(isoString)
            } catch (e: Exception) {
                null
            }
        }
    }
}
