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
Script de test local pour Mon trajet.
Permet de tester :
1. La simulation complète avec un train fictif annulé (sans clé API)
2. L'appel réel à l'API IDFM PRIM (si PRIM_API_KEY est fournie)
"""

import argparse
import json
import os
import sys

from config import PRIM_API_KEY
from fcm_client import send_cancellation_alert
from firestore_client import is_train_notified, mark_train_as_notified
from monitor import (
    extract_cancelled_trains,
    fetch_stop_monitoring,
    is_within_monitoring_window,
    parse_aimed_time,
)

# Exemple de réponse PRIM SIRI Lite contenant un train annulé pour le test
MOCK_SIRI_RESPONSE = {
    "Siri": {
        "ServiceDelivery": {
            "ResponseTimestamp": "2026-09-24T07:55:00.000Z",
            "StopMonitoringDelivery": [
                {
                    "MonitoredStopVisit": [
                        {
                            "ItemIdentifier": "MOCK-VISIT-001",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01742:"},
                                "DirectionName": [{"value": "Paris-Montparnasse"}],
                                "DestinationName": [{"value": "Paris-Montparnasse"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165432"},
                                "JourneyNote": [{"value": "ROPO"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T08:12:00.000Z",
                                    "DepartureStatus": "cancelled",
                                },
                            },
                        },
                        {
                            "ItemIdentifier": "MOCK-VISIT-002",
                            "MonitoredVehicleJourney": {
                                "LineRef": {"value": "STIF:Line::C01742:"},
                                "DirectionName": [{"value": "Paris-Montparnasse"}],
                                "DestinationName": [{"value": "Paris-Montparnasse"}],
                                "VehicleJourneyRef": {"value": "SNCF:TN:165434"},
                                "JourneyNote": [{"value": "POMA"}],
                                "MonitoredCall": {
                                    "StopPointName": [{"value": "Meudon"}],
                                    "AimedDepartureTime": "2026-09-24T08:27:00.000Z",
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


def test_mock():
    print("=" * 60)
    print(" 🧪 TEST DE SIMULATION (MOCK)")
    print("=" * 60)

    print("\n1. Détection de la plage horaire :")
    in_window, reason, active_dirs = is_within_monitoring_window()
    print(f"   -> Plage horaire active ? {'OUI' if in_window else 'NON'} ({reason})")
    print(f"   -> Directions actives : {active_dirs}")

    print("\n2. Extraction des trains annulés depuis le mock SIRI Lite :")
    cancelled = extract_cancelled_trains(MOCK_SIRI_RESPONSE)
    print(f"   -> Nombre de trains annulés détectés : {len(cancelled)}")

    for t in cancelled:
        print(f"      - ID : {t['id']}")
        print(f"        Mission : {t['mission_code']}")
        print(f"        Départ prévu : {t['departure_time']}")
        print(f"        Destination : {t['destination']}")
        print(f"        Statut : {t['status']}")

        print(f"\n3. Test de déduplication pour {t['id']} :")
        already = is_train_notified(t['id'])
        print(f"   -> Déjà notifié ? {already}")

        print("\n4. Test d'envoi d'alerte :")
        send_cancellation_alert(t)

        print("\n5. Marquage du train comme notifié :")
        mark_train_as_notified(t)
        print(f"   -> Maintenant vérifié comme notifié : {is_train_notified(t['id'])}")

    print("\n✅ Test de simulation terminé avec succès !")


def test_real_api():
    print("=" * 60)
    print(" 📡 TEST API IDFM PRIM EN DIRECT")
    print("=" * 60)

    if not PRIM_API_KEY:
        print("❌ Erreur : PRIM_API_KEY n'est pas définie dans votre fichier .env")
        print("Créez votre clé sur https://prim.iledefrance-mobilites.fr/ et ajoutez-la dans .env")
        sys.exit(1)

    print(f"Interrogation de l'API PRIM pour la gare de Meudon...")
    try:
        data = fetch_stop_monitoring()
        print("✅ Réponse HTTP 200 reçue avec succès de l'API PRIM.")

        # Affichage d'un aperçu des prochains départs
        visits = (
            data.get("Siri", {})
            .get("ServiceDelivery", {})
            .get("StopMonitoringDelivery", [{}])[0]
            .get("MonitoredStopVisit", [])
        )
        print(f"Nombre de passages trouvés : {len(visits)}")

        for i, visit in enumerate(visits[:5], 1):
            j = visit.get("MonitoredVehicleJourney", {})
            c = j.get("MonitoredCall", {})
            dest = " ".join([d.get("value", "") for d in j.get("DestinationName", [])])
            dep = c.get("AimedDepartureTime", "")
            status = c.get("DepartureStatus", "inconnu")
            notes = [n.get("value", "") for n in j.get("JourneyNote", [])]
            mission = notes[0] if notes else ""
            print(f"  {i}. {mission} vers {dest} à {parse_aimed_time(dep)} (Statut: {status})")

        cancelled = extract_cancelled_trains(data)
        print(f"\nTrains annulés vers Montparnasse détectés actuellement : {len(cancelled)}")
        for t in cancelled:
            print(f"  ⚠️ {t['departure_time']} - Mission {t['mission_code']} ({t['status']})")

    except Exception as e:
        print(f"❌ Erreur lors de l'appel API : {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test local du moniteur Mon trajet")
    parser.add_argument("--real", action="store_true", help="Tester avec la vraie API PRIM")
    args = parser.parse_args()

    if args.real:
        test_real_api()
    else:
        test_mock()
