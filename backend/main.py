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

from __future__ import annotations
from datetime import datetime, timezone
import logging
import os
from typing import Any, Dict, Optional, Tuple

try:
    import functions_framework
    from flask import Request, jsonify
except ImportError:
    class _MockFramework:
        @staticmethod
        def http(f):
            return f
    functions_framework = _MockFramework()
    Request = Any
    def jsonify(data, *args, **kwargs):
        return data

from fcm_client import send_cancellation_alert
from firestore_client import (
    cancel_quick_monitoring,
    cancel_return_commute,
    get_live_departures,
    get_monitoring_schedule,
    get_stats_summary,
    is_train_notified,
    mark_train_as_notified,
    record_backend_stats,
    record_manual_surveillance_stat,
    save_live_departures,
    trigger_quick_monitoring,
    trigger_return_commute,
    update_last_check_timestamp,
    update_monitoring_schedule,
)
from config import (
    ADMIN_SECRET_KEY,
    ALLOW_SIMULATION_ENDPOINT,
    APP_CHECK_ENFORCED,
    BACKEND_API_KEY,
    DEPARTURES_CACHE_TTL_SECONDS,
)
from monitor import (
    extract_all_departures,
    extract_cancelled_trains,
    extract_disrupted_trains,
    fetch_stop_monitoring,
    is_within_monitoring_window,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("mon-trajet.main")


def run_cancellation_check(force: bool = False) -> Dict[str, Any]:
    """
    Exécute le cycle complet de vérification :
    1. Validation des critères dynamiques (jours choisis, plage horaire, pause/snooze, fréquence)
    2. Appel API IDFM PRIM (UNIQUEMENT si les critères sont validés)
    3. Détection des suppressions
    4. Déduplication et envoi des notifications FCM
    """
    schedule = get_monitoring_schedule()
    is_active, reason, active_directions = is_within_monitoring_window(schedule=schedule)

    if not force and not is_active:
        return {
            "status": "skipped",
            "message": reason,
            "alerts_sent": 0,
            "active_directions": [],
            "schedule": {
                "enabled": schedule.get("enabled", True),
                "active_days": schedule.get("active_days"),
                "morning": f"{schedule.get('morning_start_hour', 7):02d}h{schedule.get('morning_start_minute', 0):02d} - {schedule.get('morning_end_hour', 9):02d}h{schedule.get('morning_end_minute', 30):02d}",
                "evening": f"{schedule.get('evening_start_hour', 17):02d}h{schedule.get('evening_start_minute', 0):02d} - {schedule.get('evening_end_hour', 19):02d}h{schedule.get('evening_end_minute', 30):02d}",
                "return_commute_until": schedule.get("return_commute_until"),
                "paused_until": schedule.get("paused_until"),
            },
        }

    try:
        # Met à jour le timestamp de la vérification pour respecter la fréquence
        update_last_check_timestamp()

        # Appel API PRIM garanti uniquement lors des jours/horaires demandés
        data = fetch_stop_monitoring()
        # Enregistre le tableau complet des départs pour consultation temps réel
        all_deps = extract_all_departures(data)
        save_live_departures(all_deps)

        dirs_to_check = active_directions if active_directions else ["TO_PARIS", "TO_MEUDON"]
        notify_delays = schedule.get("notify_delays", True)
        min_delay = int(schedule.get("min_delay_minutes", 5))

        disrupted_trains = extract_disrupted_trains(
            data,
            active_directions=dirs_to_check,
            include_delays=notify_delays,
            min_delay_minutes=min_delay,
        )

        alerts_sent = 0
        skipped_already_notified = 0
        details = []

        for train in disrupted_trains:
            train_id = train["id"]
            if is_train_notified(train_id):
                skipped_already_notified += 1
                logger.info(f"Train {train_id} déjà notifié précédemment. Ignoré.")
                continue

            # Envoi de la notification push FCM (annulations ou retards)
            success = send_cancellation_alert(train)
            if success:
                mark_train_as_notified(train)
                alerts_sent += 1
                details.append({
                    "train_id": train_id,
                    "departure": train["departure_time"],
                    "mission": train["mission_code"],
                    "direction": train.get("direction_label", ""),
                    "status": train.get("status", ""),
                    "status_code": train.get("status_code", "CANCELLED"),
                    "delay_minutes": train.get("delay_minutes", 0),
                })

        cancelled_detected = len([t for t in disrupted_trains if t.get("status_code") == "CANCELLED"])
        delayed_detected = len([t for t in disrupted_trains if t.get("status_code") == "DELAYED"])

        # Enregistrement des statistiques
        is_scheduled = is_active and not schedule.get("quick_monitoring_until") and not schedule.get("return_commute_until")
        record_backend_stats(
            scheduled_check=is_scheduled,
            cancellations_count=cancelled_detected,
            delays_count=delayed_detected,
            alerts_sent=alerts_sent,
        )

        return {
            "status": "success",
            "active_directions": dirs_to_check,
            "disrupted_detected": len(disrupted_trains),
            "cancelled_detected": cancelled_detected,
            "delayed_detected": delayed_detected,
            "total_departures": len(all_deps),
            "alerts_sent": alerts_sent,
            "already_notified": skipped_already_notified,
            "notified_trains": details,
        }

    except Exception as e:
        logger.error(f"Erreur lors de l'exécution de la surveillance: {e}", exc_info=True)
        return {
            "status": "error",
            "message": str(e),
            "alerts_sent": 0,
        }


def verify_admin_authorization(request: Request) -> Tuple[bool, str, int]:
    """
    Vérifie l'autorisation d'accès aux routes d'administration et de test (/simulate-alert).
    Exige la clé ADMIN_SECRET_KEY si configurée, et vérifie ALLOW_SIMULATION_ENDPOINT.
    """
    allow_sim = os.getenv("ALLOW_SIMULATION_ENDPOINT", str(ALLOW_SIMULATION_ENDPOINT)).lower() in ("true", "1", "yes")
    if not allow_sim:
        return False, "L'endpoint de simulation est désactivé sur cet environnement.", 403

    admin_key = os.getenv("ADMIN_SECRET_KEY", ADMIN_SECRET_KEY)
    if not admin_key:
        # En production Cloud Run / Cloud Functions, la clé admin est obligatoire
        if os.getenv("K_SERVICE") or os.getenv("FUNCTION_TARGET"):
            return False, "Accès refusé : la variable ADMIN_SECRET_KEY doit être définie sur le serveur.", 401
        return True, "OK (mode dev)", 200

    provided_key = (
        request.headers.get("X-Admin-Key")
        or request.args.get("admin_key")
        or (request.get_json(silent=True) or {}).get("admin_key")
    )
    if provided_key != admin_key:
        return False, "Non autorisé : clé admin (X-Admin-Key) manquante ou invalide.", 401

    return True, "OK", 200


def verify_client_authorization(request: Request) -> Tuple[bool, str, int]:
    """
    Vérifie l'autorisation des requêtes clientes (App Android, Widget) :
    1. Accepte les appels internes Google Cloud (Cloud Scheduler OIDC)
    2. Vérifie Firebase App Check (Play Integrity) si activé (APP_CHECK_ENFORCED)
    3. Vérifie la clé d'application BACKEND_API_KEY si configurée
    """
    # 1. Autoriser les requêtes internes Cloud Scheduler
    user_agent = request.headers.get("User-Agent", "")
    if "Google-Cloud-Scheduler" in user_agent or request.headers.get("X-CloudScheduler"):
        return True, "Cloud Scheduler", 200

    # 2. Vérification Firebase App Check si token présent
    app_check_enforced = os.getenv("APP_CHECK_ENFORCED", str(APP_CHECK_ENFORCED)).lower() in ("true", "1", "yes")
    app_check_token = request.headers.get("X-Firebase-AppCheck")
    if app_check_token:
        try:
            from firebase_admin import app_check
            app_check.verify_token(app_check_token)
            return True, "App Check valide", 200
        except Exception as e:
            logger.warning(f"Validation App Check échouée: {e}")
            if app_check_enforced:
                return False, "Jeton App Check invalide", 403

    if app_check_enforced and not app_check_token:
        return False, "Jeton App Check (Play Integrity) requis.", 403

    # 3. Vérification de la clé d'API applicative (X-API-Key) si configurée
    backend_key = os.getenv("BACKEND_API_KEY", BACKEND_API_KEY)
    if backend_key:
        provided_api_key = request.headers.get("X-API-Key") or request.args.get("api_key")
        if provided_api_key != backend_key:
            return False, "Non autorisé : clé d'API (X-API-Key) invalide ou absente.", 401

    return True, "OK", 200


@functions_framework.http
def check_trains_http(request: Request):
    """
    Point d'entrée HTTP pour Cloud Run functions,
    déclenché par Cloud Scheduler ou l'application Android.
    """
    action = request.args.get("action", "")

    # Vérification d'autorisation pour les requêtes clientes (App Android & Widget)
    is_client_action = (
        action in ("departures", "get_config", "set_config", "trigger_quick", "quick_monitoring", "stats", "cancel_quick", "trigger_return", "cancel_return")
        or request.path.endswith(("/departures", "/config", "/trigger-quick", "/cancel-quick", "/trigger-return", "/cancel-return", "/stats"))
    )
    if is_client_action:
        client_ok, client_msg, client_code = verify_client_authorization(request)
        if not client_ok:
            logger.warning(f"Accès client refusé ({request.path}): {client_msg}")
            return jsonify({"status": "error", "message": client_msg}), client_code

    # 1. Endpoint de lecture de configuration : GET ?action=get_config ou path /config
    if request.method == "GET" and (action == "get_config" or request.path.endswith("/config")):
        return jsonify(get_monitoring_schedule()), 200

    # 2. Endpoint de mise à jour de configuration : POST ?action=set_config ou path /config
    if request.method == "POST" and (action == "set_config" or request.path.endswith("/config")):
        body = request.get_json(silent=True) or {}
        updated = update_monitoring_schedule(body)
        return jsonify({"status": "success", "config": updated}), 200

    # 3. Endpoint pour surveillance ponctuelle (Widget Android & Géolocalisation)
    if action in ("trigger_quick", "quick_monitoring") or request.path.endswith("/trigger-quick"):
        body = request.get_json(silent=True) or {}
        duration = int(request.args.get("duration", body.get("duration", body.get("duration_minutes", 60))))
        direction = str(request.args.get("direction", body.get("direction", "AUTO")))
        notify_delays = request.args.get("notify_delays", str(body.get("notify_delays", True))).lower() in ("true", "1", "yes")
        source = str(request.args.get("source", body.get("source", "app")))
        updated = trigger_quick_monitoring(duration_minutes=duration, direction=direction, notify_delays=notify_delays)
        record_manual_surveillance_stat(source=source)
        return jsonify({
            "status": "success",
            "message": f"Surveillance ponctuelle ({direction}) activée pour {duration} min",
            "config": updated,
        }), 200

    # Endpoint pour consulter les statistiques : GET ?action=stats ou path /stats
    if request.method == "GET" and (action == "stats" or request.path.endswith("/stats")):
        stats = get_stats_summary()
        return jsonify({"status": "success", "stats": stats}), 200

    # 4. Endpoint pour annuler la surveillance ponctuelle
    if action in ("cancel_quick", "stop_quick") or request.path.endswith("/cancel-quick"):
        updated = cancel_quick_monitoring()
        return jsonify({
            "status": "success",
            "message": "Surveillance ponctuelle désactivée",
            "config": updated,
        }), 200

    # 5. Endpoint pour déclencher la surveillance retour (Paris ➔ Meudon)
    if action in ("trigger_return", "start_return") or request.path.endswith("/trigger-return"):
        body = request.get_json(silent=True) or {}
        hours = int(request.args.get("hours", body.get("hours", 1)))
        updated = trigger_return_commute(hours=hours)
        return jsonify({
            "status": "success",
            "message": f"Surveillance retour (Paris ➔ Meudon) activée pour {hours}h",
            "config": updated,
        }), 200

    # 6. Endpoint pour annuler la surveillance retour
    if action in ("cancel_return", "stop_return") or request.path.endswith("/cancel-return"):
        updated = cancel_return_commute()
        return jsonify({
            "status": "success",
            "message": "Surveillance retour désactivée",
            "config": updated,
        }), 200

    # 7. Endpoint pour simuler l'envoi d'une alerte d'annulation ou de retard (Test Push)
    if action in ("simulate_alert", "simulate_cancellation", "test_alert") or request.path.endswith("/simulate-alert"):
        # Vérification d'autorisation administrateur stricte (X-Admin-Key)
        auth_ok, auth_msg, auth_code = verify_admin_authorization(request)
        if not auth_ok:
            logger.warning(f"Accès refusé à /simulate-alert: {auth_msg}")
            return jsonify({"status": "error", "message": auth_msg}), auth_code

        body = request.get_json(silent=True) or {}
        mission = str(request.args.get("mission", body.get("mission", "ROPO")))
        dep_time = str(request.args.get("time", body.get("time", "08:12")))
        direction = str(request.args.get("direction", body.get("direction", "TO_PARIS")))
        is_delay = str(request.args.get("type", body.get("type", "cancel"))).lower() in ("delay", "retard")
        delay_min = int(request.args.get("delay", body.get("delay", 10)))

        is_to_paris = direction != "TO_MEUDON"
        dest = "Paris-Montparnasse" if is_to_paris else "Meudon"
        stop = "Meudon" if is_to_paris else "Paris-Montparnasse"

        mock_train = {
            "id": f"simulated_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}",
            "mission_code": mission,
            "departure_time": dep_time,
            "stop_name": stop,
            "destination": dest,
            "direction_code": direction,
            "direction_label": "Meudon ➔ Paris-Montparnasse" if is_to_paris else "Paris-Montparnasse ➔ Meudon",
            "status": f"RETARDÉ (+{delay_min} min)" if is_delay else "ANNULÉ",
            "status_code": "DELAYED" if is_delay else "CANCELLED",
            "is_cancelled": not is_delay,
            "delay_minutes": delay_min if is_delay else 0,
        }

        success = send_cancellation_alert(mock_train)
        return jsonify({
            "status": "success" if success else "warning",
            "fcm_sent": success,
            "simulated_train": mock_train,
            "message": (
                "Notification push envoyée avec succès sur le topic FCM"
                if success
                else "Alerte simulée (Firebase Admin non connecté ou indisponible)"
            ),
        }), 200

    # 5. Endpoint pour consulter tous les départs : GET ?action=departures ou path /departures
    if request.method == "GET" and (action == "departures" or request.path.endswith("/departures")):
        fresh = request.args.get("fresh", "").lower() in ("true", "1", "yes")
        force = request.args.get("force", "").lower() in ("true", "1", "yes")
        live = get_live_departures()

        # Calcul de l'âge du cache existant
        now = datetime.now(timezone.utc)
        is_stale = True
        updated_at_str = live.get("updated_at")
        age_seconds = None
        if updated_at_str:
            try:
                updated_at_dt = datetime.fromisoformat(updated_at_str.replace("Z", "+00:00"))
                age_seconds = (now - updated_at_dt).total_seconds()
                is_stale = (age_seconds > DEPARTURES_CACHE_TTL_SECONDS)
            except Exception as e:
                logger.warning(f"Erreur calcul âge cache départs ({updated_at_str}): {e}")
                is_stale = True

        # Déclenche l'appel PRIM uniquement si les données sont périmées, absentes ou explicitement forcées
        if is_stale or fresh or force or not live.get("departures"):
            try:
                raw_data = fetch_stop_monitoring()
                deps = extract_all_departures(raw_data)
                save_live_departures(deps)
                live = {
                    "status": "success",
                    "stop_name": "Meudon",
                    "departures": deps,
                    "updated_at": now.isoformat(),
                    "cached": False,
                    "cache_age_seconds": 0,
                }
                logger.info(
                    f"Consultation ponctuelle : {len(deps)} départs récupérés depuis l'API PRIM (fraîcheur renouvelée)."
                )
            except Exception as e:
                logger.error(f"Erreur récupération des départs depuis PRIM : {e}")
                # En cas d'erreur de l'API PRIM, retourner le cache existant s'il n'est pas vide
                if live.get("departures"):
                    live["cached"] = True
                    live["cache_age_seconds"] = int(age_seconds) if age_seconds is not None else -1
                    live["warning"] = f"Échec rafraîchissement temps réel ({e}), données en cache renvoyées."
                    return jsonify(live), 200
                return jsonify({"status": "error", "message": str(e), "departures": []}), 500
        else:
            live["cached"] = True
            live["cache_age_seconds"] = int(age_seconds) if age_seconds is not None else 0
            logger.info(
                f"Consultation ponctuelle : départs servis depuis le cache (âge: {int(age_seconds)}s < {DEPARTURES_CACHE_TTL_SECONDS}s, 0 appel PRIM)."
            )

        return jsonify(live), 200

    # 6. Cycle régulier de surveillance des trains
    force_param = request.args.get("force", "").lower() in ("true", "1", "yes")
    result = run_cancellation_check(force=force_param)

    status_code = 200 if result.get("status") in ("success", "skipped") else 500
    return jsonify(result), status_code


if __name__ == "__main__":
    # Permet de tester directement : python main.py
    import json
    res = run_cancellation_check(force=True)
    print(json.dumps(res, indent=2, ensure_ascii=False))
