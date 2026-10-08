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

enum class DepartureStatus {
    ON_TIME,
    DELAYED,
    CANCELLED
}

data class TrainDeparture(
    val id: String,
    val missionCode: String,
    val destination: String,
    val direction: String,
    val aimedTime: String,
    val expectedTime: String,
    val platform: String,
    val status: DepartureStatus,
    val statusLabel: String,
    val delayMinutes: Int,
    val isCancelled: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "mission_code" to missionCode,
        "destination" to destination,
        "direction" to direction,
        "aimed_time" to aimedTime,
        "expected_time" to expectedTime,
        "platform" to platform,
        "status" to status.name,
        "status_label" to statusLabel,
        "delay_minutes" to delayMinutes,
        "is_cancelled" to isCancelled
    )

    fun toJsonObject(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("mission_code", missionCode)
            put("destination", destination)
            put("direction", direction)
            put("aimed_time", aimedTime)
            put("expected_time", expectedTime)
            put("platform", platform)
            put("status", status.name)
            put("status_label", statusLabel)
            put("delay_minutes", delayMinutes)
            put("is_cancelled", isCancelled)
        }
    }

    companion object {
        fun fromJsonObject(obj: org.json.JSONObject): TrainDeparture {
            val map = mutableMapOf<String, Any?>()
            obj.keys().forEach { key ->
                map[key] = obj.opt(key)
            }
            return fromMap(map)
        }

        fun fromMap(map: Map<String, Any?>): TrainDeparture {
            val id = (map["id"] as? String) ?: ""
            val mission = (map["mission_code"] as? String) ?: ""
            val destination = (map["destination"] as? String) ?: "Paris-Montparnasse"
            val direction = (map["direction"] as? String) ?: "Paris-Montparnasse"
            val aimedTime = (map["aimed_time"] as? String) ?: "--:--"
            val expectedTime = (map["expected_time"] as? String) ?: aimedTime
            val platform = (map["platform"] as? String) ?: ""
            val isCancelled = (map["is_cancelled"] as? Boolean) ?: false
            val delayMinutes = (map["delay_minutes"] as? Number)?.toInt() ?: 0
            val statusLabel = (map["status_label"] as? String) ?: "À l'heure"

            val statusStr = (map["status"] as? String)?.uppercase() ?: "ON_TIME"
            val status = when {
                isCancelled || statusStr == "CANCELLED" -> DepartureStatus.CANCELLED
                delayMinutes >= 2 || statusStr == "DELAYED" -> DepartureStatus.DELAYED
                else -> DepartureStatus.ON_TIME
            }

            return TrainDeparture(
                id = id,
                missionCode = mission,
                destination = destination,
                direction = direction,
                aimedTime = aimedTime,
                expectedTime = expectedTime,
                platform = platform,
                status = status,
                statusLabel = statusLabel,
                delayMinutes = delayMinutes,
                isCancelled = isCancelled
            )
        }
    }
}
