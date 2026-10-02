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
import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from config import (
    ACTIVE_DAYS,
    DEFAULT_FREQUENCY_MINUTES,
    END_HOUR,
    END_MINUTE,
    EVENING_END_HOUR,
    EVENING_END_MINUTE,
    EVENING_START_HOUR,
    EVENING_START_MINUTE,
    FIRESTORE_COLLECTION,
    FIREBASE_PROJECT_ID,
    SCHEDULE_DOCUMENT,
    SETTINGS_COLLECTION,
    START_HOUR,
    START_MINUTE,
)

logger = logging.getLogger("null-track.firestore")

_db = None
_in_memory_cache = set()
_in_memory_schedule = {
    "enabled": True,
    "active_days": ACTIVE_DAYS,
    "frequency_minutes": DEFAULT_FREQUENCY_MINUTES,
    "paused_until": None,
    # Sens 1 : Meudon -> Paris-Montparnasse (Matin)
    "morning_enabled": True,
    "morning_start_hour": START_HOUR,
    "morning_start_minute": START_MINUTE,
    "morning_end_hour": END_HOUR,
    "morning_end_minute": END_MINUTE,
    # Sens 2 : Paris-Montparnasse -> Meudon (Soir)
    "evening_enabled": True,
    "evening_start_hour": EVENING_START_HOUR,
    "evening_start_minute": EVENING_START_MINUTE,
    "evening_end_hour": EVENING_END_HOUR,
    "evening_end_minute": EVENING_END_MINUTE,
    # Déclencheur ponctuel retour travail (ex: 1h ou 2h)
    "return_commute_until": None,
    # Surveillance ponctuelle par Widget / Géolocalisation (durée paramétrable ex: 60 min)
    "quick_monitoring_until": None,
    "quick_monitoring_direction": "AUTO", # "TO_PARIS", "TO_MEUDON" ou "AUTO"
    "quick_monitoring_duration_minutes": 60,
    "notify_delays": True,
    "min_delay_minutes": 5,
    "auto_stop_at_destination": True,
    "last_check_timestamp": 0,
}


def get_firestore_client():
    """Initialise ou réutilise le client Google Cloud Firestore."""
    global _db
    if _db is not None:
        return _db

    try:
        from google.cloud import firestore

        if FIREBASE_PROJECT_ID:
            _db = firestore.Client(project=FIREBASE_PROJECT_ID)
        else:
            _db = firestore.Client()
        logger.info("Connexion à Cloud Firestore initialisée avec succès.")
        return _db
    except Exception as e:
        logger.warning(
            f"Firestore non disponible ({e}). Utilisation du cache mémoire local (non persistant)."
        )
        return None


def is_train_notified(train_id: str) -> bool:
    """
    Vérifie si une alerte pour ce train a déjà été enregistrée dans Firestore
    (ou dans le cache local si hors-ligne).
    """
    db = get_firestore_client()
    if db is None:
        return train_id in _in_memory_cache

    try:
        doc_ref = db.collection(FIRESTORE_COLLECTION).document(train_id)
        doc = doc_ref.get()
        return doc.exists
    except Exception as e:
        logger.error(f"Erreur lors de la lecture Firestore pour {train_id}: {e}")
        return train_id in _in_memory_cache


def mark_train_as_notified(train: Dict[str, Any]) -> None:
    """
    Enregistre le train comme notifié dans Firestore avec un horodatage
    et la date d'expiration pour la rétention automatique.
    """
    train_id = train["id"]
    _in_memory_cache.add(train_id)

    db = get_firestore_client()
    if db is None:
        return

    try:
        now = datetime.now(timezone.utc)
        doc_ref = db.collection(FIRESTORE_COLLECTION).document(train_id)
        doc_ref.set({
            "train_id": train_id,
            "journey_ref": train.get("journey_ref"),
            "mission_code": train.get("mission_code"),
            "departure_time": train.get("departure_time"),
            "destination": train.get("destination"),
            "stop_name": train.get("stop_name"),
            "notified_at": now,
            "expires_at": now + timedelta(days=2),  # Nettoyage automatique TTL
        })
        logger.info(f"Train {train_id} enregistré dans Firestore comme notifié.")
    except Exception as e:
        logger.error(f"Erreur lors de l'écriture Firestore pour {train_id}: {e}")


def get_monitoring_schedule() -> Dict[str, Any]:
    """
    Récupère la configuration dynamique de surveillance depuis Firestore (settings/monitoring_schedule).
    Retourne la configuration par défaut si le document n'existe pas encore.
    """
    global _in_memory_schedule
    db = get_firestore_client()
    if db is None:
        return _in_memory_schedule.copy()

    try:
        doc_ref = db.collection(SETTINGS_COLLECTION).document(SCHEDULE_DOCUMENT)
        doc = doc_ref.get()
        if doc.exists:
            data = doc.to_dict() or {}
            # Fusion avec les valeurs par défaut
            merged = _in_memory_schedule.copy()
            merged.update(data)
            _in_memory_schedule = merged
            return merged
        else:
            # Initialise le document par défaut dans Firestore
            doc_ref.set(_in_memory_schedule)
            return _in_memory_schedule.copy()
    except Exception as e:
        logger.error(f"Erreur lors de la lecture de la configuration Firestore: {e}")
        return _in_memory_schedule.copy()


def update_monitoring_schedule(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Met à jour la configuration dynamique dans Firestore.
    """
    global _in_memory_schedule
    _in_memory_schedule.update(data)
    _in_memory_schedule["updated_at"] = datetime.now(timezone.utc).isoformat()

    db = get_firestore_client()
    if db is not None:
        try:
            doc_ref = db.collection(SETTINGS_COLLECTION).document(SCHEDULE_DOCUMENT)
            doc_ref.set(_in_memory_schedule, merge=True)
            logger.info("Configuration de surveillance mise à jour dans Firestore.")
        except Exception as e:
            logger.error(f"Erreur lors de la mise à jour de la configuration Firestore: {e}")

    return _in_memory_schedule.copy()


def update_last_check_timestamp(ts: Optional[float] = None) -> None:
    """Met à jour le timestamp de la dernière exécution."""
    global _in_memory_schedule
    now_ts = ts or datetime.now(timezone.utc).timestamp()
    _in_memory_schedule["last_check_timestamp"] = now_ts

    db = get_firestore_client()
    if db is not None:
        try:
            doc_ref = db.collection(SETTINGS_COLLECTION).document(SCHEDULE_DOCUMENT)
            doc_ref.set({"last_check_timestamp": now_ts}, merge=True)
        except Exception:
            pass


_in_memory_departures = {"departures": [], "updated_at": None, "stop_name": "Meudon"}


def save_live_departures(departures: List[Dict[str, Any]], stop_name: str = "Meudon") -> None:
    """Enregistre le tableau des départs dans Firestore (collection live_status)."""
    global _in_memory_departures
    now_iso = datetime.now(timezone.utc).isoformat()
    data = {
        "stop_name": stop_name,
        "departures": departures,
        "updated_at": now_iso,
    }
    _in_memory_departures = data
    db = get_firestore_client()
    if db is not None:
        try:
            doc_ref = db.collection("live_status").document(f"departures_{stop_name.lower()}")
            doc_ref.set(data)
            logger.info(f"{len(departures)} départs enregistrés dans Firestore (live_status).")
        except Exception as e:
            logger.error(f"Erreur écriture départs temps réel dans Firestore: {e}")


def get_live_departures(stop_name: str = "Meudon") -> Dict[str, Any]:
    """Récupère le dernier tableau des départs enregistré."""
    global _in_memory_departures
    db = get_firestore_client()
    if db is not None:
        try:
            doc_ref = db.collection("live_status").document(f"departures_{stop_name.lower()}")
            doc = doc_ref.get()
            if doc.exists:
                return doc.to_dict() or _in_memory_departures
        except Exception as e:
            logger.error(f"Erreur lecture départs temps réel dans Firestore: {e}")
    return _in_memory_departures


def trigger_return_commute(hours: int = 1) -> Dict[str, Any]:
    """
    Active la surveillance ponctuelle du retour (Paris-Montparnasse ➔ Meudon)
    pour une durée définie (ex. 1h ou 2h) quand l'utilisateur quitte le travail.
    """
    until_dt = datetime.now(timezone.utc) + timedelta(hours=hours)
    logger.info(f"Surveillance retour activée pour {hours}h (jusqu'à {until_dt.isoformat()}).")
    return update_monitoring_schedule({
        "return_commute_until": until_dt.isoformat(),
        "enabled": True,
    })


def cancel_return_commute() -> Dict[str, Any]:
    """Annule la surveillance ponctuelle du retour."""
    logger.info("Surveillance retour désactivée.")
    return update_monitoring_schedule({
        "return_commute_until": None,
    })


def trigger_quick_monitoring(
    duration_minutes: int = 60,
    direction: str = "AUTO",
    notify_delays: bool = True,
) -> Dict[str, Any]:
    """
    Active la surveillance ponctuelle (via widget ou app) pour une durée paramétrable (ex: 60 min).
    La direction est automatiquement déterminée selon la position ('TO_PARIS', 'TO_MEUDON' ou 'AUTO').
    """
    until_dt = datetime.now(timezone.utc) + timedelta(minutes=duration_minutes)
    logger.info(
        f"Surveillance ponctuelle activée pour {duration_minutes} min (direction: {direction}) jusqu'à {until_dt.isoformat()}."
    )
    return update_monitoring_schedule({
        "quick_monitoring_until": until_dt.isoformat(),
        "quick_monitoring_direction": direction,
        "quick_monitoring_duration_minutes": duration_minutes,
        "notify_delays": notify_delays,
        "enabled": True,
    })


def cancel_quick_monitoring() -> Dict[str, Any]:
    """Annule la surveillance ponctuelle (Widget)."""
    logger.info("Surveillance ponctuelle annulée.")
    return update_monitoring_schedule({
        "quick_monitoring_until": None,
        "return_commute_until": None,
    })


_in_memory_stats: Dict[str, Any] = {
    "total_manual_surveillances": 0,
    "surveillances_from_app": 0,
    "surveillances_from_widget": 0,
    "total_scheduled_checks": 0,
    "total_cancellations": 0,
    "total_delays": 0,
    "total_alerts_sent": 0,
    "total_checks": 0,
    "prim_calls_total": 0,
    "prim_calls_2xx": 0,
    "prim_calls_4xx": 0,
    "prim_calls_5xx": 0,
    "last_prim_status": None,
    "last_prim_call": None,
    "last_updated": None,
}


def record_prim_call_stat(status_code: int) -> None:
    """Enregistre un appel à l'API IDFM PRIM et catégorise le statut HTTP (2xx, 4xx, 5xx)."""
    global _in_memory_stats
    now_iso = datetime.now(timezone.utc).isoformat()
    today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    is_2xx = 200 <= status_code < 300
    is_4xx = 400 <= status_code < 500
    is_5xx = status_code >= 500 or status_code < 200

    _in_memory_stats["prim_calls_total"] += 1
    if is_2xx:
        _in_memory_stats["prim_calls_2xx"] += 1
    elif is_4xx:
        _in_memory_stats["prim_calls_4xx"] += 1
    elif is_5xx:
        _in_memory_stats["prim_calls_5xx"] += 1
    _in_memory_stats["last_prim_status"] = status_code
    _in_memory_stats["last_prim_call"] = now_iso
    _in_memory_stats["last_updated"] = now_iso

    db = get_firestore_client()
    if db is not None:
        try:
            from google.cloud import firestore

            updates: Dict[str, Any] = {
                "prim_calls_total": firestore.Increment(1),
                "last_prim_status": status_code,
                "last_prim_call": now_iso,
                "last_updated": now_iso,
            }
            if is_2xx:
                updates["prim_calls_2xx"] = firestore.Increment(1)
            elif is_4xx:
                updates["prim_calls_4xx"] = firestore.Increment(1)
            elif is_5xx:
                updates["prim_calls_5xx"] = firestore.Increment(1)

            db.collection(SETTINGS_COLLECTION).document("stats").set(updates, merge=True)

            daily_updates: Dict[str, Any] = {
                "date": today_str,
                "prim_calls_total": firestore.Increment(1),
                "last_updated": now_iso,
            }
            if is_2xx:
                daily_updates["prim_calls_2xx"] = firestore.Increment(1)
            elif is_4xx:
                daily_updates["prim_calls_4xx"] = firestore.Increment(1)
            elif is_5xx:
                daily_updates["prim_calls_5xx"] = firestore.Increment(1)

            db.collection(SETTINGS_COLLECTION).document(f"stats_daily_{today_str}").set(daily_updates, merge=True)
            logger.info(f"Statistique appel PRIM ({status_code}) enregistrée dans Firestore.")
        except Exception as e:
            logger.error(f"Erreur enregistrement stat PRIM Firestore: {e}")


def record_backend_stats(
    scheduled_check: bool = False,
    cancellations_count: int = 0,
    delays_count: int = 0,
    alerts_sent: int = 0,
) -> None:
    """Enregistre et incrémente les statistiques d'usage et de ponctualité dans Firestore."""
    global _in_memory_stats
    now_iso = datetime.now(timezone.utc).isoformat()
    today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    _in_memory_stats["total_checks"] += 1
    if scheduled_check:
        _in_memory_stats["total_scheduled_checks"] += 1
    _in_memory_stats["total_cancellations"] += cancellations_count
    _in_memory_stats["total_delays"] += delays_count
    _in_memory_stats["total_alerts_sent"] += alerts_sent
    _in_memory_stats["last_updated"] = now_iso

    db = get_firestore_client()
    if db is not None:
        try:
            from google.cloud import firestore

            summary_updates: Dict[str, Any] = {
                "total_checks": firestore.Increment(1),
                "total_cancellations": firestore.Increment(cancellations_count),
                "total_delays": firestore.Increment(delays_count),
                "total_alerts_sent": firestore.Increment(alerts_sent),
                "last_updated": now_iso,
            }
            if scheduled_check:
                summary_updates["total_scheduled_checks"] = firestore.Increment(1)
                summary_updates["last_scheduled_check"] = now_iso

            db.collection(SETTINGS_COLLECTION).document("stats").set(summary_updates, merge=True)

            daily_updates: Dict[str, Any] = {
                "date": today_str,
                "checks": firestore.Increment(1),
                "cancellations": firestore.Increment(cancellations_count),
                "delays": firestore.Increment(delays_count),
                "alerts_sent": firestore.Increment(alerts_sent),
                "last_updated": now_iso,
            }
            if scheduled_check:
                daily_updates["scheduled_checks"] = firestore.Increment(1)

            db.collection(SETTINGS_COLLECTION).document(f"stats_daily_{today_str}").set(daily_updates, merge=True)
            logger.info("Statistiques d'usage mises à jour dans Firestore.")
        except Exception as e:
            logger.error(f"Erreur mise à jour des statistiques Firestore: {e}")


def record_manual_surveillance_stat(source: str = "app") -> None:
    """Incrémente le compteur de surveillance manuelle déclenchée."""
    global _in_memory_stats
    now_iso = datetime.now(timezone.utc).isoformat()
    _in_memory_stats["total_manual_surveillances"] += 1
    if source == "widget":
        _in_memory_stats["surveillances_from_widget"] += 1
    else:
        _in_memory_stats["surveillances_from_app"] += 1

    db = get_firestore_client()
    if db is not None:
        try:
            from google.cloud import firestore

            updates: Dict[str, Any] = {
                "total_manual_surveillances": firestore.Increment(1),
                "last_manual_trigger": now_iso,
                "last_updated": now_iso,
            }
            if source == "widget":
                updates["surveillances_from_widget"] = firestore.Increment(1)
            else:
                updates["surveillances_from_app"] = firestore.Increment(1)

            db.collection(SETTINGS_COLLECTION).document("stats").set(updates, merge=True)
        except Exception as e:
            logger.error(f"Erreur incrément stats manuelles Firestore: {e}")


def get_stats_summary() -> Dict[str, Any]:
    """Récupère le résumé consolidé des statistiques depuis Firestore."""
    global _in_memory_stats
    db = get_firestore_client()
    if db is not None:
        try:
            doc = db.collection(SETTINGS_COLLECTION).document("stats").get()
            if doc.exists:
                data = doc.to_dict() or {}
                _in_memory_stats.update(data)
                return _in_memory_stats.copy()
        except Exception as e:
            logger.error(f"Erreur lecture statistiques Firestore: {e}")
    return _in_memory_stats.copy()


