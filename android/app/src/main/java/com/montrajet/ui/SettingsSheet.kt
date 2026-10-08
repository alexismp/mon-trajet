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

package com.montrajet.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montrajet.data.AppStats
import com.montrajet.data.ScheduleConfig
import com.montrajet.data.TrainAlert
import com.montrajet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    schedule: ScheduleConfig,
    alerts: List<TrainAlert> = emptyList(),
    stats: AppStats = AppStats(),
    isSubscribed: Boolean = true,
    onDismiss: () -> Unit,
    onSaveSchedule: (ScheduleConfig) -> Unit,
    onPauseHours: (Int) -> Unit = {},
    onPauseToday: () -> Unit = {},
    onResumeNow: () -> Unit = {},
    onTestAlertClick: () -> Unit = {},
    onClearHistoryClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var enabled by remember(schedule) { mutableStateOf(schedule.enabled) }

    // Plage Matin (Meudon ➔ Paris)
    var morningEnabled by remember(schedule) { mutableStateOf(schedule.morningEnabled) }
    var morningStartHour by remember(schedule) { mutableStateOf(schedule.morningStartHour) }
    var morningStartMinute by remember(schedule) { mutableStateOf(schedule.morningStartMinute) }
    var morningEndHour by remember(schedule) { mutableStateOf(schedule.morningEndHour) }
    var morningEndMinute by remember(schedule) { mutableStateOf(schedule.morningEndMinute) }

    // Plage Soir (Paris ➔ Meudon)
    var eveningEnabled by remember(schedule) { mutableStateOf(schedule.eveningEnabled) }
    var eveningStartHour by remember(schedule) { mutableStateOf(schedule.eveningStartHour) }
    var eveningStartMinute by remember(schedule) { mutableStateOf(schedule.eveningStartMinute) }
    var eveningEndHour by remember(schedule) { mutableStateOf(schedule.eveningEndHour) }
    var eveningEndMinute by remember(schedule) { mutableStateOf(schedule.eveningEndMinute) }

    var activeDays by remember(schedule) { mutableStateOf(schedule.activeDays) }
    var frequencyMinutes by remember(schedule) { mutableStateOf(schedule.frequencyMinutes) }
    var quickDuration by remember(schedule) { mutableStateOf(schedule.quickMonitoringDurationMinutes) }
    var notifyDelays by remember(schedule) { mutableStateOf(schedule.notifyDelays) }
    var autoStopAtDestination by remember(schedule) { mutableStateOf(schedule.autoStopAtDestination) }

    var showHistorySection by remember { mutableStateOf(false) }

    val dayLetters = listOf("L", "M", "M", "J", "V", "S", "D")

    fun showNativeTimePicker(
        initialHour: Int,
        initialMinute: Int,
        onTimeSelected: (Int, Int) -> Unit
    ) {
        TimePickerDialog(
            context,
            { _, hour, minute ->
                onTimeSelected(hour, minute)
            },
            initialHour,
            initialMinute,
            true // Format 24h standard
        ).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLight,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Titre et bouton fermer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = TransilienN,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Options & Paramètres",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = TextPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Fermer", modifier = Modifier.size(24.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = Color(0xFFEEEEEE))

            // ==========================================
            // 1. SURVEILLANCE PROGRAMMÉE
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BackgroundLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Surveillance Programmée",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimary
                        )

                        val (statusIcon, statusTint, statusLabel) = when {
                            !enabled -> Triple(Icons.Default.Warning, Color(0xFFD32F2F), "Désactivée")
                            schedule.isPaused() -> Triple(Icons.Default.PauseCircle, Color(0xFFE65100), "En pause")
                            isSubscribed -> Triple(Icons.Default.CheckCircle, SuccessGreen, "Active")
                            else -> Triple(Icons.Default.CheckCircle, TextSecondary, "Connexion...")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = statusLabel, color = statusTint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Activer la surveillance automatique",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Surveille automatiquement les trains selon vos plages horaires et jours de travail",
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = TransilienN
                            )
                        )
                    }

                    // Si actuellement en pause, proposer la reprise directe
                    if (schedule.isPaused()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onResumeNow,
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reprendre la surveillance maintenant", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 2. JOURS SURVEILLÉS (Style natif Android / Réveil)
            // ==========================================
            Text(
                text = "📅 Jours surveillés :",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextPrimary
            )
            Text(
                text = "Aucun appel API n'est effectué les jours non sélectionnés",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Rangée de 7 boutons circulaires équilibrés
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dayLetters.forEachIndexed { index, letter ->
                    val isSelected = activeDays.contains(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(if (isSelected) TransilienN else Color(0xFFF1F5F9))
                            .clickable {
                                activeDays = if (isSelected) {
                                    activeDays.filter { it != index }
                                } else {
                                    (activeDays + index).sorted()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // Raccourcis pratiques (Semaine / Tous les jours)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isWeekdays = activeDays == listOf(0, 1, 2, 3, 4)
                val isAllDays = activeDays == listOf(0, 1, 2, 3, 4, 5, 6)

                FilterChip(
                    selected = isWeekdays,
                    onClick = { activeDays = listOf(0, 1, 2, 3, 4) },
                    label = { Text("Semaine (Lun-Ven)", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TransilienN.copy(alpha = 0.15f),
                        selectedLabelColor = TransilienN
                    )
                )
                FilterChip(
                    selected = isAllDays,
                    onClick = { activeDays = listOf(0, 1, 2, 3, 4, 5, 6) },
                    label = { Text("Tous les jours", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TransilienN.copy(alpha = 0.15f),
                        selectedLabelColor = TransilienN
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 3. PLAGE MATIN (Sélecteur d'horaire natif)
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BackgroundLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌅 Matin : Meudon ➔ Paris",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Switch(
                            checked = morningEnabled,
                            onCheckedChange = { morningEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TransilienN)
                        )
                    }

                    if (morningEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TimePickerCard(
                                label = "Début",
                                hour = morningStartHour,
                                minute = morningStartMinute,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showNativeTimePicker(morningStartHour, morningStartMinute) { h, m ->
                                        morningStartHour = h
                                        morningStartMinute = m
                                        // Ajuster la fin si nécessaire
                                        if (morningEndHour < h || (morningEndHour == h && morningEndMinute < m)) {
                                            morningEndHour = (h + 1).coerceAtMost(23)
                                            morningEndMinute = m
                                        }
                                    }
                                }
                            )

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )

                            TimePickerCard(
                                label = "Fin",
                                hour = morningEndHour,
                                minute = morningEndMinute,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showNativeTimePicker(morningEndHour, morningEndMinute) { h, m ->
                                        morningEndHour = h
                                        morningEndMinute = m
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 4. PLAGE SOIR (Sélecteur d'horaire natif)
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BackgroundLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌆 Soir : Paris ➔ Meudon",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Switch(
                            checked = eveningEnabled,
                            onCheckedChange = { eveningEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TransilienN)
                        )
                    }

                    if (eveningEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TimePickerCard(
                                label = "Début",
                                hour = eveningStartHour,
                                minute = eveningStartMinute,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showNativeTimePicker(eveningStartHour, eveningStartMinute) { h, m ->
                                        eveningStartHour = h
                                        eveningStartMinute = m
                                        if (eveningEndHour < h || (eveningEndHour == h && eveningEndMinute < m)) {
                                            eveningEndHour = (h + 1).coerceAtMost(23)
                                            eveningEndMinute = m
                                        }
                                    }
                                }
                            )

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )

                            TimePickerCard(
                                label = "Fin",
                                hour = eveningEndHour,
                                minute = eveningEndMinute,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showNativeTimePicker(eveningEndHour, eveningEndMinute) { h, m ->
                                        eveningEndHour = h
                                        eveningEndMinute = m
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 5. PARAMÈTRES DU WIDGET & SURVEILLANCE RAPIDE
            // ==========================================
            Text(
                text = "⏱️ Durée par défaut de la surveillance ponctuelle :",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(30 to "30 min", 60 to "1 heure ⭐", 90 to "1h30", 120 to "2 heures").forEach { (duration, label) ->
                    val isSelected = quickDuration == duration
                    FilterChip(
                        selected = isSelected,
                        onClick = { quickDuration = duration },
                        label = { Text(label, fontSize = 14.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TransilienN,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 6. NOTIFICATIONS DES RETARDS
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BackgroundLight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⏱️ Notifier les retards (≥ 5 min)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Alerter aussi en cas de train retardé en plus des suppressions fermes",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                    Switch(
                        checked = notifyDelays,
                        onCheckedChange = { notifyDelays = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TransilienN)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ==========================================
            // ARRÊT AUTOMATIQUE À DESTINATION (GPS)
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🏁 Arrêt auto à destination (GPS)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Coupe automatiquement les alertes et les appels API dès votre arrivée à Paris ou à Meudon",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                    Switch(
                        checked = autoStopAtDestination,
                        onCheckedChange = { autoStopAtDestination = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TransilienN)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==========================================
            // 7. FRÉQUENCE DES VÉRIFICATIONS
            // ==========================================
            Text(
                text = "📡 Fréquence des vérifications :",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(3, 5, 10, 15).forEach { min ->
                    val isSelected = frequencyMinutes == min
                    FilterChip(
                        selected = isSelected,
                        onClick = { frequencyMinutes = min },
                        label = { Text("${min} min${if (min == 3) " ⭐" else ""}", fontSize = 14.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TransilienN,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            Text(
                text = "Les vérifications API sont exécutées toutes les $frequencyMinutes minutes pendant vos fenêtres de surveillance actives.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(22.dp))

            // ==========================================
            // 8. TEST D'ALERTE PUSH
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8FD))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = TransilienN,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Test d'alerte push",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Vérifiez que votre appareil reçoit bien les notifications sonores et vibratoires en générant une alerte test immédiate.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onTestAlertClick,
                        colors = ButtonDefaults.buttonColors(containerColor = TransilienN),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tester une alerte push maintenant", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // 9. HISTORIQUE DES ALERTES & NETTOYAGE
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Historique des alertes (${alerts.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                if (alerts.isNotEmpty()) {
                    TextButton(onClick = onClearHistoryClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFC62828)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Effacer", color = Color(0xFFC62828), fontSize = 14.sp)
                    }
                }
            }

            if (alerts.isEmpty()) {
                Text(
                    text = "Aucune alerte reçue récemment.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                TextButton(
                    onClick = { showHistorySection = !showHistorySection },
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = if (showHistorySection) "Masquer les détails" else "Afficher les ${alerts.size} alertes reçues",
                        fontSize = 14.sp,
                        color = TransilienN,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showHistorySection) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        alerts.forEach { alert ->
                            AlertCard(alert = alert)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // 10. STATISTIQUES D'USAGE & PERTURBATIONS
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BackgroundLight)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📊", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Statistiques d'usage & Ligne N",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ligne 1 : Surveillances demandées (Manuelles vs Programmées)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Surveillances manuelles",
                            count = stats.totalManualSurveillances,
                            subtitle = "App: ${stats.surveillancesFromApp} • Widget: ${stats.surveillancesFromWidget}",
                            icon = "👆",
                            accentColor = TransilienN
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Cycles programmés",
                            count = stats.totalScheduledChecks,
                            subtitle = "Matin & Soir",
                            icon = "⏰",
                            accentColor = SuccessGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Ligne 2 : Perturbations détectées (Annulations, Retards, Alertes)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Annulations",
                            count = stats.totalCancellations,
                            subtitle = "Trains supprimés",
                            icon = "🛑",
                            accentColor = Color(0xFFC62828)
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Retards (≥ 5m)",
                            count = stats.totalDelays,
                            subtitle = "Trains retardés",
                            icon = "⏱️",
                            accentColor = Color(0xFFE65100)
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Alertes",
                            count = stats.totalAlertsSent,
                            subtitle = "Notifications",
                            icon = "🔔",
                            accentColor = TransilienN
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Ligne 3 : Appels API PRIM (SIRI Lite)
                    Text(
                        text = "🌐 Appels API PRIM (Île-de-France Mobilités)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "Total",
                            count = stats.primCallsTotal,
                            subtitle = "Requêtes",
                            icon = "📡",
                            accentColor = TransilienN
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "2xx",
                            count = stats.primCalls2xx,
                            subtitle = "Succès",
                            icon = "✅",
                            accentColor = SuccessGreen
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "4xx",
                            count = stats.primCalls4xx,
                            subtitle = "Client",
                            icon = "⚠️",
                            accentColor = Color(0xFFE65100)
                        )
                        StatCounterCard(
                            modifier = Modifier.weight(1f),
                            title = "5xx",
                            count = stats.primCalls5xx,
                            subtitle = "Serveur",
                            icon = "❌",
                            accentColor = Color(0xFFC62828)
                        )
                    }

                    if (stats.lastPrimStatus != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val statusColor = when {
                            stats.lastPrimStatus in 200..299 -> SuccessGreen
                            stats.lastPrimStatus in 400..499 -> Color(0xFFE65100)
                            else -> Color(0xFFC62828)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Dernier appel PRIM : ",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "HTTP ${stats.lastPrimStatus}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            if (!stats.lastPrimCall.isNullOrBlank()) {
                                Text(
                                    text = " (${stats.lastPrimCall.take(16).replace('T', ' ')})",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    if (stats.lastManualTrigger != null || stats.lastScheduledCheck != null || stats.lastUpdated != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Dernière synchro : ${stats.lastUpdated?.take(16)?.replace('T', ' ') ?: "En cours..."}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // ==========================================
            // BOUTON ENREGISTRER
            // ==========================================
            Button(
                onClick = {
                    val updated = schedule.copy(
                        enabled = enabled,
                        morningEnabled = morningEnabled,
                        morningStartHour = morningStartHour,
                        morningStartMinute = morningStartMinute,
                        morningEndHour = morningEndHour,
                        morningEndMinute = morningEndMinute,
                        eveningEnabled = eveningEnabled,
                        eveningStartHour = eveningStartHour,
                        eveningStartMinute = eveningStartMinute,
                        eveningEndHour = eveningEndHour,
                        eveningEndMinute = eveningEndMinute,
                        activeDays = activeDays,
                        frequencyMinutes = frequencyMinutes,
                        quickMonitoringDurationMinutes = quickDuration,
                        notifyDelays = notifyDelays,
                        autoStopAtDestination = autoStopAtDestination
                    )
                    onSaveSchedule(updated)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TransilienN)
            ) {
                Text("Enregistrer les options", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Carte de sélection horaire élégante qui ouvre le TimePickerDialog natif Android.
 */
@Composable
fun TimePickerCard(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = SurfaceLight,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.FRANCE, "%02d:%02d", hour, minute),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Modifier l'heure",
                tint = TransilienN,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun AlertCard(alert: TrainAlert) {
    val timeFormat = SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE)
    val formattedDate = timeFormat.format(Date(alert.receivedAtTimestamp))

    val isToParis = alert.destination.contains("Paris", ignoreCase = true) ||
            alert.destination.contains("Montparnasse", ignoreCase = true)
    val directionLabel = if (isToParis) "Meudon ➔ Paris" else "Paris ➔ Meudon"

    val isDelayed = alert.status.contains("RETARD", ignoreCase = true)

    val cardBg = if (isDelayed) Color(0xFFFFF3E0) else CancelledRedLight
    val badgeBg = if (isDelayed) Color(0xFFE65100) else CancelledRed
    val badgeText = if (isDelayed) "RETARDÉ" else "ANNULÉ"
    val icon = if (isDelayed) Icons.Default.Schedule else Icons.Default.Warning

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Train de ${alert.departureTime}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = badgeBg
                    )
                    Text(
                        text = badgeText,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = badgeBg
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Mission : ${alert.missionCode} • $directionLabel",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Terminus : ${alert.destination}",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reçue le $formattedDate",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun StatCounterCard(
    modifier: Modifier = Modifier,
    title: String,
    count: Int,
    subtitle: String,
    icon: String,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = icon, fontSize = 16.sp)
                Text(
                    text = "$count",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = accentColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

