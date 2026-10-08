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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montrajet.data.DepartureStatus
import com.montrajet.data.TrainDeparture
import com.montrajet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesSheet(
    departures: List<TrainDeparture>,
    lastUpdated: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("Montparnasse") }

    val filteredDepartures = remember(departures, selectedFilter) {
        when (selectedFilter) {
            "Montparnasse" -> departures.filter {
                it.destination.contains("Montparnasse", ignoreCase = true) ||
                it.destination.contains("Paris", ignoreCase = true)
            }
            "Banlieue" -> departures.filter {
                !it.destination.contains("Montparnasse", ignoreCase = true) &&
                !it.destination.contains("Paris", ignoreCase = true)
            }
            else -> departures
        }
    }

    val cancelledCount = departures.count { it.status == DepartureStatus.CANCELLED }
    val delayedCount = departures.count { it.status == DepartureStatus.DELAYED }
    val onTimeCount = departures.count { it.status == DepartureStatus.ON_TIME }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLight,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // En-tête
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(TransilienN),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "N",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Gare de Meudon",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Prochains départs • ${lastUpdated ?: "Chargement..."}",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = TransilienN,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Rafraîchir",
                                tint = TransilienN
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Fermer")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Badges récapitulatifs (Annulés, Retardés, À l'heure)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (cancelledCount > 0) {
                    StatusSummaryChip(
                        count = cancelledCount,
                        label = "Annulé${if (cancelledCount > 1) "s" else ""}",
                        containerColor = Color(0xFFFFEBEE),
                        textColor = Color(0xFFC62828)
                    )
                }
                if (delayedCount > 0) {
                    StatusSummaryChip(
                        count = delayedCount,
                        label = "En retard",
                        containerColor = Color(0xFFFFF3E0),
                        textColor = Color(0xFFE65100)
                    )
                }
                StatusSummaryChip(
                    count = onTimeCount,
                    label = "À l'heure",
                    containerColor = Color(0xFFE8F5E9),
                    textColor = Color(0xFF2E7D32)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Filtres de direction
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "Montparnasse",
                    onClick = { selectedFilter = "Montparnasse" },
                    label = { Text("Vers Paris (Montparnasse)", fontSize = 14.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TransilienN,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedFilter == "Tous",
                    onClick = { selectedFilter = "Tous" },
                    label = { Text("Tous", fontSize = 14.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TransilienN,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedFilter == "Banlieue",
                    onClick = { selectedFilter = "Banlieue" },
                    label = { Text("Vers Banlieue", fontSize = 14.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TransilienN,
                        selectedLabelColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredDepartures.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.DirectionsTransit,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Aucun passage prévu pour cette direction.",
                            color = TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxHeight(0.85f)
                ) {
                    items(filteredDepartures, key = { it.id }) { departure ->
                        DepartureCard(departure = departure)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusSummaryChip(
    count: Int,
    label: String,
    containerColor: Color,
    textColor: Color
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$count",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
fun DepartureCard(departure: TrainDeparture) {
    val isCancelled = departure.status == DepartureStatus.CANCELLED
    val isDelayed = departure.status == DepartureStatus.DELAYED

    val cardBg = when {
        isCancelled -> Color(0xFFFFF1F1)
        isDelayed -> Color(0xFFFFFBEA)
        else -> SurfaceLight
    }

    val borderColor = when {
        isCancelled -> Color(0xFFFFCDD2)
        isDelayed -> Color(0xFFFFE082)
        else -> Color(0xFFE0E0E0)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Heures et destination
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Code mission badge (ex: POGI)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCancelled) Color(0xFFB71C1C) else TransilienN)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = departure.missionCode.ifBlank { "N" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = departure.destination,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (departure.platform.isNotBlank()) {
                        Text(
                            text = "Voie ${departure.platform}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Text(
                            text = " • ",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "Prévu : ${departure.aimedTime}",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }

            // Horaires réels et Badge de statut
            Column(horizontalAlignment = Alignment.End) {
                if (isCancelled) {
                    Text(
                        text = departure.aimedTime,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C),
                        textDecoration = TextDecoration.LineThrough
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFC62828))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "ANNULÉ",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                } else if (isDelayed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = departure.aimedTime,
                            fontSize = 15.sp,
                            color = TextSecondary,
                            textDecoration = TextDecoration.LineThrough
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = departure.expectedTime,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEF6C00))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = departure.statusLabel,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        text = departure.aimedTime,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E7D32))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "À L'HEURE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
