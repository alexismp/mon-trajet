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

package com.montrajet.location

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.montrajet.data.ScheduleRepository
import com.montrajet.widget.MonTrajetWidgetProvider

/**
 * Récepteur d'événements pour l'arrivée à destination par géolocalisation
 * et pour l'action manuelle "J'ai pris le train" depuis la notification.
 */
class DestinationArrivalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Événement reçu : $action")

        val repository = ScheduleRepository.getInstance(context)
        val schedule = repository.schedule.value

        when (action) {
            ACTION_DESTINATION_ARRIVED -> {
                // Vérifier si l'appareil pénètre dans la zone de proximité
                val isEntering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
                if (!isEntering) {
                    Log.d(TAG, "Sortie de zone de proximité, ignoré.")
                    return
                }

                if (!schedule.isMonitoringActiveNow()) {
                    Log.d(TAG, "Arrivée détectée mais surveillance déjà inactive ou en pause.")
                    return
                }

                if (!schedule.autoStopAtDestination) {
                    Log.d(TAG, "Arrivée détectée mais l'arrêt automatique par géolocalisation est désactivé.")
                    return
                }

                val direction = intent.getStringExtra(EXTRA_DIRECTION) ?: schedule.getActiveDirectionCode() ?: "AUTO"
                Log.i(TAG, "🏁 Arrivée à destination confirmée ($direction) par géolocalisation !")

                // 1. Met en pause jusqu'au prochain créneau (synchronise également Firestore)
                repository.pauseUntilNextCommute(source = "geolocation_arrival")

                // 2. Annule la Proximity Alert active
                LocationHelper.unregisterDestinationProximityAlert(context)

                // 3. Annule toutes les notifications en cours
                val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notifManager?.cancelAll()

                // 4. Met à jour les widgets
                MonTrajetWidgetProvider.updateAllWidgets(context)

                val destLabel = if (direction == "TO_PARIS") "Paris-Montparnasse" else "Meudon"
                val remainingText = repository.schedule.value.getPausedRemainingText() ?: "votre prochain trajet"
                showToast(context, "🏁 Arrivé à $destLabel ! Surveillance et alertes coupées jusqu'à $remainingText.")
            }

            ACTION_TRAIN_TAKEN -> {
                Log.i(TAG, "Action 'J'ai pris le train' déclenchée par l'utilisateur.")

                // 1. Met en pause jusqu'au prochain créneau
                repository.pauseUntilNextCommute(source = "user_train_taken")

                // 2. Annule la Proximity Alert active
                LocationHelper.unregisterDestinationProximityAlert(context)

                // 3. Annule toutes les notifications en cours
                val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notifManager?.cancelAll()

                // 4. Met à jour les widgets
                MonTrajetWidgetProvider.updateAllWidgets(context)

                val remainingText = repository.schedule.value.getPausedRemainingText() ?: "votre prochain trajet"
                showToast(context, "🚆 Bon voyage ! Surveillance et alertes suspendues jusqu'à $remainingText.")
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "DestinationArrival"
        const val ACTION_DESTINATION_ARRIVED = "com.montrajet.location.ACTION_DESTINATION_ARRIVED"
        const val ACTION_TRAIN_TAKEN = "com.montrajet.location.ACTION_TRAIN_TAKEN"
        const val EXTRA_DIRECTION = "extra_direction"
    }
}
