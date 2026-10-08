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

package com.montrajet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.montrajet.data.AlertRepository
import com.montrajet.data.DeparturesRepository
import com.montrajet.data.ScheduleRepository
import com.montrajet.data.StatsRepository
import com.montrajet.data.TrainAlert
import com.montrajet.location.LocationHelper
import com.montrajet.service.MonTrajetMessagingService
import com.montrajet.ui.HomeScreen
import com.montrajet.ui.SettingsSheet
import com.montrajet.ui.theme.MonTrajetTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: AlertRepository
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var departuresRepository: DeparturesRepository
    private lateinit var statsRepository: StatsRepository
    private var isSubscribedToTopic by mutableStateOf(false)

    // Demande des permissions Notifications & Localisation
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Permission localisation accordée pour détection du sens de trajet")
            com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
        }
        if (!notifGranted) {
            Toast.makeText(
                this,
                "Veuillez autoriser les notifications pour recevoir les alertes",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AlertRepository.getInstance(applicationContext)
        scheduleRepository = ScheduleRepository.getInstance(applicationContext)
        departuresRepository = DeparturesRepository.getInstance(applicationContext)
        statsRepository = StatsRepository.getInstance(applicationContext)

        handleOpenDeparturesIntent(intent)
        checkAppPermissions()
        subscribeToFCMTopic()

        setContent {
            MonTrajetTheme {
                val alerts by repository.alerts.collectAsState()
                val schedule by scheduleRepository.schedule.collectAsState()
                val departures by departuresRepository.departures.collectAsState()
                val lastUpdatedDepartures by departuresRepository.lastUpdated.collectAsState()
                val isDeparturesLoading by departuresRepository.isLoading.collectAsState()
                val stats by statsRepository.stats.collectAsState()

                var showSettingsSheet by remember { mutableStateOf(false) }

                val isMonitoringActive = schedule.isMonitoringActiveNow()
                LaunchedEffect(isMonitoringActive) {
                    if (isMonitoringActive) {
                        Log.d(TAG, "Surveillance active détectée : rafraîchissement immédiat des trains (force=true)")
                        departuresRepository.refresh(force = true)
                    }
                }

                HomeScreen(
                    schedule = schedule,
                    departures = departures,
                    lastUpdatedDepartures = lastUpdatedDepartures,
                    isDeparturesLoading = isDeparturesLoading,
                    onSettingsClick = { showSettingsSheet = true },
                    onRefreshDepartures = { departuresRepository.refresh(force = true) },
                    onTriggerQuickMonitoring = { duration, direction ->
                        scheduleRepository.resumeNow()
                        scheduleRepository.triggerQuickMonitoring(duration, direction)
                        LocationHelper.registerDestinationProximityAlert(applicationContext, direction)
                        departuresRepository.refresh(force = true)
                        com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
                        val dirLabel = if (direction == "TO_PARIS") "Meudon ➔ Paris" else "Paris ➔ Meudon"
                        Toast.makeText(
                            this@MainActivity,
                            "Surveillance activée pour ${duration} min ($dirLabel)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onCancelQuickMonitoring = {
                        scheduleRepository.pauseUntilNextCommute(source = "app_cancel")
                        LocationHelper.unregisterDestinationProximityAlert(applicationContext)
                        com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
                        Toast.makeText(
                            this@MainActivity,
                            "Surveillance mise en pause jusqu'au prochain trajet",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onTrainTaken = {
                        scheduleRepository.pauseUntilNextCommute(source = "app_train_taken")
                        LocationHelper.unregisterDestinationProximityAlert(applicationContext)
                        com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
                        val remainingText = scheduleRepository.schedule.value.getPausedRemainingText() ?: "votre prochain trajet"
                        Toast.makeText(
                            this@MainActivity,
                            "🚆 Bon voyage ! Surveillance et alertes coupées jusqu'à $remainingText",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onResumeSurveillance = {
                        scheduleRepository.resumeNow()
                        com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
                        departuresRepository.refresh(force = true)
                        Toast.makeText(this@MainActivity, "Surveillance reprise", Toast.LENGTH_SHORT).show()
                    }
                )

                if (showSettingsSheet) {
                    SettingsSheet(
                        schedule = schedule,
                        alerts = alerts,
                        stats = stats,
                        isSubscribed = isSubscribedToTopic,
                        onDismiss = { showSettingsSheet = false },
                        onSaveSchedule = { newSchedule ->
                            scheduleRepository.saveConfig(newSchedule)
                        },
                        onPauseHours = { hours ->
                            scheduleRepository.pauseForHours(hours)
                        },
                        onPauseToday = {
                            scheduleRepository.pauseForToday()
                        },
                        onResumeNow = {
                            scheduleRepository.resumeNow()
                        },
                        onTestAlertClick = { sendLocalTestAlert() },
                        onClearHistoryClick = { repository.clearAlerts() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenDeparturesIntent(intent)
    }

    private fun handleOpenDeparturesIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DEPARTURES, false) == true) {
            val isMonitoringActive = scheduleRepository.schedule.value.isMonitoringActiveNow()
            departuresRepository.refresh(force = isMonitoringActive)
            com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        var isMonitoringActive = scheduleRepository.schedule.value.isMonitoringActiveNow()
        val schedule = scheduleRepository.schedule.value

        // Vérification d'arrivée à destination par géolocalisation si surveillance active
        val activeDir = schedule.getActiveDirectionCode()
        if (isMonitoringActive && schedule.autoStopAtDestination && activeDir != null) {
            if (LocationHelper.isArrivedAtDestination(applicationContext, activeDir)) {
                Log.i(TAG, "🏁 Arrivée à destination ($activeDir) détectée au retour au premier plan !")
                scheduleRepository.pauseUntilNextCommute(source = "app_resume_arrival_check")
                LocationHelper.unregisterDestinationProximityAlert(applicationContext)
                isMonitoringActive = false
                val destLabel = if (activeDir == "TO_PARIS") "Paris-Montparnasse" else "Meudon"
                val remainingText = scheduleRepository.schedule.value.getPausedRemainingText() ?: "votre prochain trajet"
                Toast.makeText(
                    this,
                    "🏁 Arrivé à $destLabel ! Surveillance coupée jusqu'à $remainingText.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                LocationHelper.registerDestinationProximityAlert(applicationContext, activeDir)
            }
        }

        Log.d(TAG, "MainActivity onResume (surveillance active: $isMonitoringActive)")
        departuresRepository.refresh(force = isMonitoringActive)
        statsRepository.refresh()
        com.montrajet.widget.MonTrajetWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun checkAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun subscribeToFCMTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic(MonTrajetMessagingService.TOPIC_NAME)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    isSubscribedToTopic = true
                    Log.d(TAG, "Abonné au topic ${MonTrajetMessagingService.TOPIC_NAME}")
                } else {
                    isSubscribedToTopic = false
                    Log.e(TAG, "Échec abonnement topic", task.exception)
                }
            }
    }

    private fun sendLocalTestAlert() {
        val testAlert = TrainAlert(
            id = "test_${System.currentTimeMillis()}",
            missionCode = "ROPO",
            departureTime = "08:12",
            stopName = "Meudon",
            destination = "Paris-Montparnasse",
            status = "ANNULÉ"
        )
        repository.addAlert(testAlert)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MonTrajetMessagingService.CHANNEL_ID,
                "Alertes Trains Annulés",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val testIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_DEPARTURES, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            999,
            testIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val builder = NotificationCompat.Builder(this, MonTrajetMessagingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Test Alerte : ROPO (08:12)")
            .setContentText("Le train de 08:12 au départ de Meudon vers Paris-Montparnasse est supprimé.")
            .setSound(defaultSound)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(999, builder.build())
        Toast.makeText(this, "Alerte de test déclenchée ! Cliquez sur la notification pour voir les départs.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_OPEN_DEPARTURES = "open_departures"
    }
}
