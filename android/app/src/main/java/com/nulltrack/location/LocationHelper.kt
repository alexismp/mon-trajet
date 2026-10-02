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

package com.nulltrack.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

enum class CommuteDirection(val code: String, val label: String) {
    TO_PARIS("TO_PARIS", "Meudon ➔ Paris"),
    TO_MEUDON("TO_MEUDON", "Paris ➔ Meudon")
}

data class CommuteLocationDetection(
    val direction: CommuteDirection,
    val locationLabel: String,
    val distanceToMeudonKm: Float?,
    val isEstimated: Boolean
)

object LocationHelper {

    private const val TAG = "LocationHelper"

    // Coordonnées de la gare de Meudon (Ligne N)
    const val MEUDON_LAT = 48.81423
    const val MEUDON_LNG = 2.23846

    // Coordonnées de Paris (Montparnasse)
    const val PARIS_MONTPARNASSE_LAT = 48.8412
    const val PARIS_MONTPARNASSE_LNG = 2.3205

    // Rayon de proximité avec la gare de départ / domicile : 4 km
    private const val MEUDON_PROXIMITY_RADIUS_METERS = 4000f

    // Rayons d'arrivée à destination pour l'arrêt automatique de surveillance
    const val ARRIVAL_RADIUS_PARIS_METERS = 3000f
    const val ARRIVAL_RADIUS_MEUDON_METERS = 2500f
    private const val PROXIMITY_REQUEST_CODE = 8001

    /**
     * Vérifie si les permissions de localisation sont accordées.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Détermine la direction du trajet selon la position géographique réelle.
     * - Si proche de Meudon (< 4km ou plus proche de Meudon que de Paris) => Sens Banlieue ➔ Paris.
     * - Si dans Paris / au travail => Sens Paris ➔ Banlieue (Paris ➔ Meudon).
     * - Si localisation désactivée => bascule intelligente selon l'heure de la journée.
     */
    fun detectCommuteDirection(context: Context): CommuteLocationDetection {
        if (hasLocationPermission(context)) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )

                var bestLocation: Location? = null
                for (provider in providers) {
                    try {
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null) {
                            if (bestLocation == null || loc.time > bestLocation.time) {
                                bestLocation = loc
                            }
                        }
                    } catch (ignored: SecurityException) {
                    }
                }

                if (bestLocation != null) {
                    val meudonDist = FloatArray(1)
                    Location.distanceBetween(
                        bestLocation.latitude,
                        bestLocation.longitude,
                        MEUDON_LAT,
                        MEUDON_LNG,
                        meudonDist
                    )

                    val parisDist = FloatArray(1)
                    Location.distanceBetween(
                        bestLocation.latitude,
                        bestLocation.longitude,
                        PARIS_MONTPARNASSE_LAT,
                        PARIS_MONTPARNASSE_LNG,
                        parisDist
                    )

                    val distanceToMeudonMeters = meudonDist[0]
                    val distanceToParisMeters = parisDist[0]
                    val kmToMeudon = distanceToMeudonMeters / 1000f

                    return if (distanceToMeudonMeters <= MEUDON_PROXIMITY_RADIUS_METERS || distanceToMeudonMeters < distanceToParisMeters) {
                        CommuteLocationDetection(
                            direction = CommuteDirection.TO_PARIS,
                            locationLabel = "Proche de Meudon (${String.format(java.util.Locale.FRANCE, "%.1f", kmToMeudon)} km)",
                            distanceToMeudonKm = kmToMeudon,
                            isEstimated = false
                        )
                    } else {
                        CommuteLocationDetection(
                            direction = CommuteDirection.TO_MEUDON,
                            locationLabel = "À Paris / Travail",
                            distanceToMeudonKm = kmToMeudon,
                            isEstimated = false
                        )
                    }
                }
            }
        }

        // Si localisation indisponible, estimation basée sur l'heure (matin vs après-midi/soir)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour < 13) {
            CommuteLocationDetection(
                direction = CommuteDirection.TO_PARIS,
                locationLabel = "Estimation matin : Domicile ➔ Paris",
                distanceToMeudonKm = null,
                isEstimated = true
            )
        } else {
            CommuteLocationDetection(
                direction = CommuteDirection.TO_MEUDON,
                locationLabel = "Estimation soir : Travail ➔ Meudon",
                distanceToMeudonKm = null,
                isEstimated = true
            )
        }
    }

    /**
     * Récupère la dernière position connue la plus récente et précise disponible.
     */
    fun getLastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                }
            } catch (ignored: SecurityException) {
            }
        }
        return bestLocation
    }

    /**
     * Détecte si l'utilisateur est arrivé à destination selon le sens de trajet surveillé :
     * - Pour TO_PARIS : arrivé si à moins de 3 km de Montparnasse (ou dans Paris, plus proche de Paris que de Meudon à > 5km de Meudon).
     * - Pour TO_MEUDON : arrivé si à moins de 2.5 km de Meudon (et à plus de 4 km de Paris).
     */
    fun isArrivedAtDestination(context: Context, directionCode: String): Boolean {
        val location = getLastKnownLocation(context) ?: return false
        // Tolérance d'âge de la position : maximum 45 minutes
        val locationAgeMillis = System.currentTimeMillis() - location.time
        if (locationAgeMillis > 45 * 60 * 1000L) {
            Log.d(TAG, "Position trop ancienne (${locationAgeMillis / 60000}m) pour valider l'arrivée.")
            return false
        }

        val meudonDist = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, MEUDON_LAT, MEUDON_LNG, meudonDist)
        val parisDist = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, PARIS_MONTPARNASSE_LAT, PARIS_MONTPARNASSE_LNG, parisDist)

        val distToMeudon = meudonDist[0]
        val distToParis = parisDist[0]

        return when (directionCode) {
            "TO_PARIS" -> {
                val arrived = distToParis <= ARRIVAL_RADIUS_PARIS_METERS ||
                    (distToParis < distToMeudon && distToMeudon > 5000f)
                if (arrived) {
                    Log.i(TAG, "Arrivée à Paris détectée (distance Paris: ${distToParis.toInt()}m, Meudon: ${distToMeudon.toInt()}m)")
                }
                arrived
            }
            "TO_MEUDON" -> {
                val arrived = distToMeudon <= ARRIVAL_RADIUS_MEUDON_METERS && distToParis > 4000f
                if (arrived) {
                    Log.i(TAG, "Arrivée à Meudon détectée (distance Meudon: ${distToMeudon.toInt()}m, Paris: ${distToParis.toInt()}m)")
                }
                arrived
            }
            else -> false
        }
    }

    /**
     * Enregistre une Proximity Alert système via LocationManager.
     * Dès que le smartphone franchit le rayon de la destination (même écran éteint / app fermée),
     * l'OS réveille DestinationArrivalReceiver pour mettre en pause la surveillance et cesser les requêtes.
     */
    fun registerDestinationProximityAlert(context: Context, directionCode: String) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Permissions localisation manquantes pour enregistrer la Proximity Alert.")
            return
        }
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val intent = Intent(DestinationArrivalReceiver.ACTION_DESTINATION_ARRIVED).apply {
                setPackage(context.packageName)
                putExtra(DestinationArrivalReceiver.EXTRA_DIRECTION, directionCode)
            }
            // Flag MUTABLE obligatoire sur Android 12+ pour que LocationManager puisse injecter KEY_PROXIMITY_ENTERING
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PROXIMITY_REQUEST_CODE,
                intent,
                flag
            )

            val (targetLat, targetLng, radius) = if (directionCode == "TO_PARIS") {
                Triple(PARIS_MONTPARNASSE_LAT, PARIS_MONTPARNASSE_LNG, ARRIVAL_RADIUS_PARIS_METERS)
            } else {
                Triple(MEUDON_LAT, MEUDON_LNG, ARRIVAL_RADIUS_MEUDON_METERS)
            }

            // Expiration : 12 heures
            locationManager.addProximityAlert(targetLat, targetLng, radius, 12 * 3600 * 1000L, pendingIntent)
            Log.i(TAG, "Proximity Alert système enregistrée pour destination $directionCode (${radius.toInt()}m)")
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'enregistrer la Proximity Alert système: ${e.message}")
        }
    }

    /**
     * Supprime la Proximity Alert système.
     */
    fun unregisterDestinationProximityAlert(context: Context) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val intent = Intent(DestinationArrivalReceiver.ACTION_DESTINATION_ARRIVED).apply {
                setPackage(context.packageName)
            }
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PROXIMITY_REQUEST_CODE,
                intent,
                flag
            )
            if (pendingIntent != null) {
                locationManager.removeProximityAlert(pendingIntent)
                pendingIntent.cancel()
                Log.i(TAG, "Proximity Alert système désenregistrée.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur suppression Proximity Alert: ${e.message}")
        }
    }
}
