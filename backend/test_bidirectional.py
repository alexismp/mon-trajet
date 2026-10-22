# Copyright 2026 Alexis Moussine-Pouchkine
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#!/usr/bin/env python3
"""
Tests unitaires pour la surveillance bidirectionnelle, le déclencheur retour,
le widget de surveillance ponctuelle et la détection des retards.
"""
from datetime import datetime, time, timedelta, timezone
import unittest

from monitor import (
    extract_all_departures,
    extract_cancelled_trains,
    extract_disrupted_trains,
    is_within_monitoring_window,
    TIMEZONE,
)

MOCK_BIDIRECTIONAL_SIRI = {
    "Siri": {
        "ServiceDelivery": {
            "ResponseTimestamp": "2026-09-24T17:30:00.000Z",
            "StopMonitoringDelivery": [
                {
                    "MonitoredStopVisit": [
                        # Train Aller vers Paris (annulé)
                        {
                            "ItemIdentifier": "VISIT-PARIS-001",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01736:"},
                                "DirectionRef": {"value": "Retour"},
                                "DestinationName": [{"value": "Paris-Montparnasse"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165101"},
                                "JourneyNote": [{"value": "POMA"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T08:15:00.000Z",
                                    "DepartureStatus": "cancelled",
                                },
                            },
                        },
                        # Train Aller vers Paris (retardé de 12 min)
                        {
                            "ItemIdentifier": "VISIT-PARIS-002",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01736:"},
                                "DirectionRef": {"value": "Retour"},
                                "DestinationName": [{"value": "Paris-Montparnasse"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165102"},
                                "JourneyNote": [{"value": "POGI"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T08:30:00.000Z",
                                    "ExpectedDepartureTime": "2026-09-24T08:42:00.000Z",
                                    "DepartureStatus": "delayed",
                                },
                            },
                        },
                        # Train Retour vers Rambouillet / Banlieue (annulé)
                        {
                            "ItemIdentifier": "VISIT-MEUDON-002",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01736:"},
                                "DirectionRef": {"value": "Aller"},
                                "DestinationName": [{"value": "Rambouillet"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165202"},
                                "JourneyNote": [{"value": "ROPO"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T18:15:00.000Z",
                                    "DepartureStatus": "cancelled",
                                },
                            },
                        },
                        # Train Retour vers Plaisir (retardé de 8 min)
                        {
                            "ItemIdentifier": "VISIT-MEUDON-003",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01736:"},
                                "DirectionRef": {"value": "Aller"},
                                "DestinationName": [{"value": "Plaisir - Grignon"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165203"},
                                "JourneyNote": [{"value": "POGI"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T18:30:00.000Z",
                                    "ExpectedDepartureTime": "2026-09-24T18:38:00.000Z",
                                    "DepartureStatus": "delayed",
                                },
                            },
                        },
                        # Train Retour à l'heure (aucun problème)
                        {
                            "ItemIdentifier": "VISIT-MEUDON-004",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01736:"},
                                "DirectionRef": {"value": "Aller"},
                                "DestinationName": [{"value": "Mantes-la-Jolie"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165204"},
                                "JourneyNote": [{"value": "MOPI"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T18:45:00.000Z",
                                    "DepartureStatus": "onTime",
                                },
                            },
                        },
                    ]
                }
            ]
        }
    }
}


class TestBidirectionalMonitoring(unittest.TestCase):

    def test_morning_window_detection(self):
        # Jeudi à 08h00 (plage matin 07h00 - 09h30)
        cfg = {
            "enabled": True,
            "active_days": [3],
            "morning_enabled": True,
            "morning_start_hour": 7, "morning_start_minute": 0,
            "morning_end_hour": 9, "morning_end_minute": 30,
            "evening_enabled": True,
            "evening_start_hour": 17, "evening_start_minute": 0,
            "evening_end_hour": 19, "evening_end_minute": 30,
        }
        test_now = datetime(2026, 9, 24, 8, 0, tzinfo=TIMEZONE)
        is_active, reason, active_dirs = is_within_monitoring_window(dt=test_now, schedule=cfg)
        self.assertTrue(is_active)
        self.assertEqual(active_dirs, ["TO_PARIS"])

    def test_evening_window_detection(self):
        # Jeudi à 18h00 (plage soir 17h00 - 19h30)
        cfg = {
            "enabled": True,
            "active_days": [3],
            "morning_enabled": True,
            "morning_start_hour": 7, "morning_start_minute": 0,
            "morning_end_hour": 9, "morning_end_minute": 30,
            "evening_enabled": True,
            "evening_start_hour": 17, "evening_start_minute": 0,
            "evening_end_hour": 19, "evening_end_minute": 30,
        }
        test_now = datetime(2026, 9, 24, 18, 0, tzinfo=TIMEZONE)
        is_active, reason, active_dirs = is_within_monitoring_window(dt=test_now, schedule=cfg)
        self.assertTrue(is_active)
        self.assertEqual(active_dirs, ["TO_MEUDON"])

    def test_outside_hours_without_trigger(self):
        # Jeudi à 14h00 (hors plages et aucun déclencheur)
        cfg = {
            "enabled": True,
            "active_days": [3],
            "morning_enabled": True, "morning_start_hour": 7, "morning_end_hour": 9, "morning_start_minute": 0, "morning_end_minute": 30,
            "evening_enabled": True, "evening_start_hour": 17, "evening_end_hour": 19, "evening_start_minute": 0, "evening_end_minute": 30,
        }
        test_now = datetime(2026, 9, 24, 14, 0, tzinfo=TIMEZONE)
        is_active, reason, active_dirs = is_within_monitoring_window(dt=test_now, schedule=cfg)
        self.assertFalse(is_active)
        self.assertEqual(active_dirs, [])

    def test_widget_quick_monitoring_to_meudon(self):
        # Utilisateur à Paris active le widget pour 1h (sens Paris ➔ Meudon)
        now_14h = datetime(2026, 9, 24, 14, 0, tzinfo=TIMEZONE)
        quick_until = (now_14h + timedelta(hours=1)).astimezone(timezone.utc).isoformat()
        cfg = {
            "enabled": True,
            "active_days": [0, 1, 2, 3, 4],
            "quick_monitoring_until": quick_until,
            "quick_monitoring_direction": "TO_MEUDON",
        }
        is_active, reason, active_dirs = is_within_monitoring_window(dt=now_14h, schedule=cfg)
        self.assertTrue(is_active)
        self.assertEqual(active_dirs, ["TO_MEUDON"])

    def test_widget_quick_monitoring_to_paris(self):
        # Utilisateur proche de Meudon active le widget pour 1h (sens Meudon ➔ Paris)
        now_14h = datetime(2026, 9, 24, 14, 0, tzinfo=TIMEZONE)
        quick_until = (now_14h + timedelta(hours=1)).astimezone(timezone.utc).isoformat()
        cfg = {
            "enabled": True,
            "active_days": [0, 1, 2, 3, 4],
            "quick_monitoring_until": quick_until,
            "quick_monitoring_direction": "TO_PARIS",
        }
        is_active, reason, active_dirs = is_within_monitoring_window(dt=now_14h, schedule=cfg)
        self.assertTrue(is_active)
        self.assertEqual(active_dirs, ["TO_PARIS"])

    def test_explicit_quick_monitoring_overrides_scheduled_direction(self):
        # Utilisateur déjà à Paris à 08h30 (pendant le créneau matin 07h00-09h30)
        # qui déclenche une surveillance ponctuelle TO_MEUDON :
        # ne doit PAS inclure TO_PARIS ni return_commute_until opposé.
        now_8h30 = datetime(2026, 9, 24, 8, 30, tzinfo=TIMEZONE)
        quick_until = (now_8h30 + timedelta(hours=1)).astimezone(timezone.utc).isoformat()
        cfg = {
            "enabled": True,
            "active_days": [0, 1, 2, 3, 4],
            "morning_enabled": True,
            "morning_start_hour": 7, "morning_start_minute": 0,
            "morning_end_hour": 9, "morning_end_minute": 30,
            "quick_monitoring_until": quick_until,
            "quick_monitoring_direction": "TO_MEUDON",
        }
        is_active, reason, active_dirs = is_within_monitoring_window(dt=now_8h30, schedule=cfg)
        self.assertTrue(is_active)
        self.assertEqual(active_dirs, ["TO_MEUDON"])

    def test_extract_disrupted_trains_delays_and_cancellations(self):
        # 1. Vérification sens TO_PARIS (1 annulé + 1 retardé de 12 min)
        disrupted_paris = extract_disrupted_trains(
            MOCK_BIDIRECTIONAL_SIRI,
            active_directions=["TO_PARIS"],
            include_delays=True,
            min_delay_minutes=5,
        )
        self.assertEqual(len(disrupted_paris), 2)
        codes = [t["status_code"] for t in disrupted_paris]
        self.assertIn("CANCELLED", codes)
        self.assertIn("DELAYED", codes)

        delayed_train = [t for t in disrupted_paris if t["status_code"] == "DELAYED"][0]
        self.assertEqual(delayed_train["delay_minutes"], 12)
        self.assertEqual(delayed_train["direction_code"], "TO_PARIS")

        # 2. Vérification sens TO_MEUDON (1 annulé + 1 retardé de 8 min)
        disrupted_meudon = extract_disrupted_trains(
            MOCK_BIDIRECTIONAL_SIRI,
            active_directions=["TO_MEUDON"],
            include_delays=True,
            min_delay_minutes=5,
        )
        self.assertEqual(len(disrupted_meudon), 2)
        meudon_delayed = [t for t in disrupted_meudon if t["status_code"] == "DELAYED"][0]
        self.assertEqual(meudon_delayed["delay_minutes"], 8)
        self.assertEqual(meudon_delayed["direction_code"], "TO_MEUDON")

        # 3. Sans inclusion des retards (seulement annulations)
        only_cancelled = extract_cancelled_trains(
            MOCK_BIDIRECTIONAL_SIRI,
            active_directions=["TO_PARIS", "TO_MEUDON"],
        )
        self.assertEqual(len(only_cancelled), 2)
        for t in only_cancelled:
            self.assertEqual(t["status_code"], "CANCELLED")

    def test_paris_to_meudon_uses_montparnasse_departure_time(self):
        # Simulation d'une réponse combinée Meudon + Paris-Montparnasse :
        # - Train 165657 (MOPI) déjà parti de Montparnasse à 19h04, arrive à Meudon à 19h14 (à ignorer)
        # - Train 165559 (GOPI) part de Montparnasse à 19h19 (17:19Z) voie 11, passe à Meudon à 19h29 (17:29:30Z) voie 1B
        # - Train 165460 (PORO) part de Meudon vers Paris à 19h16 (17:16Z) voie 2B
        combined_data = {
            "Siri": {
                "ServiceDelivery": {
                    "ResponseTimestamp": "2026-10-15T17:13:34.000Z",
                    "StopMonitoringDelivery": [
                        {
                            "MonitoredStopVisit": [
                                {
                                    "ItemIdentifier": "VISIT-165657",
                                    "MonitoredVehicleJourney": {
                                        "LineRef": {"value": "STIF:Line::C01736:"},
                                        "DirectionRef": {"value": "Aller"},
                                        "DestinationName": [{"value": "Mantes-la-Jolie"}],
                                        "VehicleJourneyName": [{"value": "165657"}],
                                        "JourneyNote": [{"value": "MOPI"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Meudon"}],
                                            "AimedArrivalTime": "2026-10-15T17:13:50.000Z",
                                            "AimedDepartureTime": "2026-10-15T17:14:30.000Z",
                                            "ExpectedArrivalTime": "2026-10-15T17:14:19.000Z",
                                            "ExpectedDepartureTime": "2026-10-15T17:14:59.000Z",
                                            "DeparturePlatformName": {"value": "1B"},
                                            "DepartureStatus": "onTime",
                                        },
                                    },
                                },
                                {
                                    "ItemIdentifier": "VISIT-165559",
                                    "MonitoredVehicleJourney": {
                                        "LineRef": {"value": "STIF:Line::C01736:"},
                                        "DirectionRef": {"value": "Aller"},
                                        "DestinationName": [{"value": "Plaisir - Grignon"}],
                                        "VehicleJourneyName": [{"value": "165559"}],
                                        "JourneyNote": [{"value": "GOPI"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Meudon"}],
                                            "AimedArrivalTime": "2026-10-15T17:28:50.000Z",
                                            "AimedDepartureTime": "2026-10-15T17:29:30.000Z",
                                            "ExpectedArrivalTime": "2026-10-15T17:28:50.000Z",
                                            "ExpectedDepartureTime": "2026-10-15T17:29:30.000Z",
                                            "DeparturePlatformName": {"value": "1B"},
                                            "DepartureStatus": "onTime",
                                        },
                                    },
                                },
                                {
                                    "ItemIdentifier": "VISIT-165460",
                                    "MonitoredVehicleJourney": {
                                        "LineRef": {"value": "STIF:Line::C01736:"},
                                        "DirectionRef": {"value": "Retour"},
                                        "DestinationName": [{"value": "Paris Montparnasse"}],
                                        "VehicleJourneyName": [{"value": "165460"}],
                                        "JourneyNote": [{"value": "PORO"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Meudon"}],
                                            "AimedArrivalTime": "2026-10-15T17:15:20.000Z",
                                            "AimedDepartureTime": "2026-10-15T17:16:00.000Z",
                                            "DeparturePlatformName": {"value": "2B"},
                                            "DepartureStatus": "onTime",
                                        },
                                    },
                                },
                            ]
                        }
                    ],
                }
            },
            "ParisSiri": {
                "ServiceDelivery": {
                    "ResponseTimestamp": "2026-10-15T17:13:34.000Z",
                    "StopMonitoringDelivery": [
                        {
                            "MonitoredStopVisit": [
                                {
                                    "ItemIdentifier": "PARIS-VISIT-165559",
                                    "MonitoredVehicleJourney": {
                                        "LineRef": {"value": "STIF:Line::C01736:"},
                                        "DirectionRef": {"value": "Aller"},
                                        "DestinationName": [{"value": "Plaisir - Grignon"}],
                                        "VehicleJourneyName": [{"value": "165559"}],
                                        "JourneyNote": [{"value": "GOPI"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Paris Montparnasse"}],
                                            "AimedDepartureTime": "2026-10-15T17:19:00.000Z",
                                            "ExpectedDepartureTime": "2026-10-15T17:19:00.000Z",
                                            "DeparturePlatformName": {"value": "11"},
                                            "DepartureStatus": "onTime",
                                        },
                                    },
                                }
                            ]
                        }
                    ],
                }
            },
        }

        departures = extract_all_departures(combined_data)
        # Le train 165657 (déjà parti de Montparnasse à 19h04) est filtré, il reste 165460 et 165559
        self.assertEqual(len(departures), 2)

        gopi = [d for d in departures if d["mission_code"] == "GOPI"][0]
        # Heure de départ depuis Paris-Montparnasse (19:19) et non l'arrivée à Meudon (19:29)
        self.assertEqual(gopi["aimed_time"], "19:19")
        self.assertEqual(gopi["expected_time"], "19:19")
        self.assertEqual(gopi["platform"], "11")
        self.assertEqual(gopi["direction"], "Banlieue")

        poro = [d for d in departures if d["mission_code"] == "PORO"][0]
        self.assertEqual(poro["aimed_time"], "19:16")
        self.assertEqual(poro["platform"], "2B")
        self.assertEqual(poro["direction"], "Paris-Montparnasse")

    def test_duplicate_stop_visits_are_deduplicated(self):
        duplicate_siri = {
            "Siri": {
                "ServiceDelivery": {
                    "ResponseTimestamp": "2026-10-21T07:00:00.000Z",
                    "StopMonitoringDelivery": [
                        {
                            "MonitoredStopVisit": [
                                {
                                    "ItemIdentifier": "VISIT-DUP-1",
                                    "MonitoredVehicleJourney": {
                                        "DirectionRef": {"value": "Retour"},
                                        "DestinationName": [{"value": "Paris Montparnasse"}],
                                        "FramedVehicleJourneyRef": {
                                            "DatedVehicleJourneyRef": "SNCF_MAGENTA_PRD:VehicleJourney::4cc52afa-ac74-40f9-baf8-dc15941fc0bc:LOC"
                                        },
                                        "JourneyNote": [{"value": "PORO"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Meudon"}],
                                            "AimedDepartureTime": "2026-10-21T07:16:00.000Z",
                                            "DepartureStatus": "onTime",
                                        },
                                    },
                                },
                                {
                                    "ItemIdentifier": "VISIT-DUP-2",
                                    "MonitoredVehicleJourney": {
                                        "DirectionRef": {"value": "Retour"},
                                        "DestinationName": [{"value": "Paris Montparnasse"}],
                                        "FramedVehicleJourneyRef": {
                                            "DatedVehicleJourneyRef": "SNCF_MAGENTA_PRD:VehicleJourney::4cc52afa-ac74-40f9-baf8-dc15941fc0bc:LOC"
                                        },
                                        "JourneyNote": [{"value": "PORO"}],
                                        "MonitoredCall": {
                                            "StopPointName": [{"value": "Meudon"}],
                                            "AimedDepartureTime": "2026-10-21T07:16:00.000Z",
                                            "DeparturePlatformName": {"value": "2B"},
                                            "DepartureStatus": "cancelled",
                                        },
                                    },
                                },
                            ]
                        }
                    ],
                }
            }
        }
        departures = extract_all_departures(duplicate_siri)
        self.assertEqual(len(departures), 1)
        self.assertEqual(departures[0]["status"], "CANCELLED")
        self.assertEqual(departures[0]["platform"], "2B")


if __name__ == "__main__":
    unittest.main()
