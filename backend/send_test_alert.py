#!/usr/bin/env python3
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

"""
Script CLI pour simuler et envoyer une fausse alerte de train (annulation ou retard)
sur le topic Firebase Cloud Messaging (FCM).

Usage :
    # 1. Envoi direct local (utilise vos identifiants Firebase / gcloud ADC)
    python3 backend/send_test_alert.py

    # 2. Personnalisation du train simulé
    python3 backend/send_test_alert.py --mission POMA --time 08:35 --type cancel
    python3 backend/send_test_alert.py --mission ROPO --time 18:42 --type delay --delay 15 --direction TO_MEUDON

    # 3. Déclenchement via le service Cloud Run distant (avec clé admin secrète)
    python3 backend/send_test_alert.py --remote https://votre-service.run.app --admin-key mon_secret_admin
"""

import argparse
from datetime import datetime, timezone
import json
import os
import sys

# Ajout du dossier backend au sys.path si exécuté depuis la racine
sys.path.insert(0, os.path.dirname(__file__))

from config import FCM_TOPIC
from fcm_client import send_cancellation_alert


def send_local(args):
    is_to_paris = args.direction != "TO_MEUDON"
    dest = "Paris-Montparnasse" if is_to_paris else "Meudon"
    stop = "Meudon" if is_to_paris else "Paris-Montparnasse"
    is_delay = args.type.lower() in ("delay", "retard")

    train_data = {
        "id": f"simulated_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}",
        "mission_code": args.mission,
        "departure_time": args.time,
        "stop_name": stop,
        "destination": dest,
        "direction_code": args.direction,
        "direction_label": "Meudon ➔ Paris-Montparnasse" if is_to_paris else "Paris-Montparnasse ➔ Meudon",
        "status": f"RETARDÉ (+{args.delay} min)" if is_delay else "ANNULÉ",
        "status_code": "DELAYED" if is_delay else "CANCELLED",
        "is_cancelled": not is_delay,
        "delay_minutes": args.delay if is_delay else 0,
    }

    print("=" * 60)
    print(" 🚀 ENVOI D'UNE ALERTE SIMULÉE VIA FIREBASE CLOUD MESSAGING")
    print("=" * 60)
    print(f"Mission       : {train_data['mission_code']}")
    print(f"Heure prévue  : {train_data['departure_time']}")
    print(f"Trajet        : {train_data['direction_label']}")
    print(f"Statut        : {train_data['status']}")
    print(f"Topic FCM     : {args.topic}")
    print("-" * 60)

    success = send_cancellation_alert(train_data, topic=args.topic)
    if success:
        print("✅ Notification push envoyée avec succès sur le topic !")
        print("📱 Vérifiez votre smartphone : l'alerte devrait retentir et vibrer.")
    else:
        print("⚠️ L'envoi local a échoué (Firebase Admin SDK non connecté).")
        print("Astuce : assurez-vous d'avoir exécuté 'gcloud auth application-default login'")
        print("ou utilisez l'option --remote pour déclencher via le backend déployé.")


def send_remote(remote_url, args):
    import urllib.error
    import urllib.parse
    import urllib.request

    url = remote_url.rstrip("/") + "/simulate-alert"
    params = {
        "mission": args.mission,
        "time": args.time,
        "direction": args.direction,
        "type": args.type,
        "delay": args.delay,
    }
    target_url = f"{url}?{urllib.parse.urlencode(params)}"

    print("=" * 60)
    print(" 📡 ENVOI VIA LE BACKEND DISTANT CLOUD RUN")
    print(f" URL : {target_url}")
    print("=" * 60)

    try:
        headers = {"User-Agent": "MonTrajet-CLI/1.0"}
        admin_key = args.admin_key or os.getenv("ADMIN_SECRET_KEY")
        if admin_key:
            headers["X-Admin-Key"] = admin_key

        req = urllib.request.Request(target_url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            print(json.dumps(data, indent=2, ensure_ascii=False))
            if data.get("fcm_sent"):
                print("\n✅ Notification envoyée avec succès par le backend Cloud Run !")
            else:
                print(f"\n⚠️ Réponse backend : {data.get('message')}")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode() if e.fp else ""
        print(f"❌ Erreur HTTP {e.code} : {e.reason}")
        if error_body:
            print(f"Détail : {error_body}")
    except Exception as e:
        print(f"❌ Erreur lors de l'appel distant : {e}")


def main():
    parser = argparse.ArgumentParser(description="Simule l'envoi d'une alerte d'annulation ou de retard Transilien N")
    parser.add_argument("--mission", default="ROPO", help="Code mission du train (ex: ROPO, POMA, MOPI)")
    parser.add_argument("--time", default="08:12", help="Heure prévue de passage (ex: 08:12)")
    parser.add_argument("--direction", default="TO_PARIS", choices=["TO_PARIS", "TO_MEUDON"], help="Sens de circulation")
    parser.add_argument("--type", default="cancel", choices=["cancel", "delay"], help="Type d'incident (cancel ou delay)")
    parser.add_argument("--delay", type=int, default=10, help="Retard en minutes (si --type delay)")
    parser.add_argument("--topic", default=FCM_TOPIC, help="Topic FCM destinataire")
    parser.add_argument(
        "--remote",
        default=os.getenv("BACKEND_URL"),
        help="URL du backend Cloud Run (ex: https://votre-service.run.app ou défini via BACKEND_URL)",
    )
    parser.add_argument(
        "--admin-key",
        default=os.getenv("ADMIN_SECRET_KEY"),
        help="Clé secrète d'administration requise pour /simulate-alert (ou définie via ADMIN_SECRET_KEY)",
    )

    args = parser.parse_args()

    if args.remote:
        send_remote(args.remote, args)
    else:
        send_local(args)


if __name__ == "__main__":
    main()
