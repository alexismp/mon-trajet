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

import logging
from datetime import datetime, time, timezone
from typing import Any, Dict, List, Optional, Tuple
import requests

from config import (
    ACTIVE_DAYS,
    DESTINATION_FILTER,
    END_HOUR,
    END_MINUTE,
    EVENING_END_HOUR,
    EVENING_END_MINUTE,
    EVENING_START_HOUR,
    EVENING_START_MINUTE,
    FORCE_CHECK,
    LINE_REF,
    MIN_DELAY_MINUTES,
    MONITORING_REF,
    NOTIFY_DELAYS,
    PRIM_API_KEY,
    PRIM_API_URL,
    START_HOUR,
    START_MINUTE,
    TIMEZONE,
    WINDOW_END,
    WINDOW_START,
)

logger = logging.getLogger("mon-trajet.monitor")


def is_within_monitoring_window(
    schedule: Optional[Dict[str, Any]] = None,
    dt: Optional[datetime] = None,
) -> Tuple[bool, str, List[str]]:
    """
    Vérifie si la date/heure actuelle respecte tous les critères de surveillance :
    1. Surveillance globale activée (enabled)
    2. Période de mise en veille / pause (paused_until)
    3. Déclenchement ponctuel retour travail (return_commute_until) -> active TO_MEUDON
    4. Jours de la semaine actifs (active_days) :
       - Plage Matin (morning_start à morning_end) -> active TO_PARIS
       - Plage Soir (evening_start à evening_end) -> active TO_MEUDON
    5. Intervalle de fréquence (frequency_minutes)

    Retourne (is_active: bool, reason: str, active_directions: List[str]).
    active_directions contient "TO_PARIS" (Meudon ➔ Paris) et/ou "TO_MEUDON" (Paris ➔ Meudon).
    """
    if FORCE_CHECK:
        logger.info("FORCE_CHECK est activé : vérification forcée hors plage.")
        return True, "FORCE_CHECK actif", ["TO_PARIS", "TO_MEUDON"]

    now = dt or datetime.now(TIMEZONE)
    current_day = now.weekday()
    current_time = now.time()

    cfg = schedule or {}

    # 1. Vérification interrupteur principal
    if not cfg.get("enabled", True):
        logger.info("Surveillance désactivée par l'utilisateur.")
        return False, "Surveillance désactivée dans l'application", []

    # 2. Vérification de la pause temporaire (Snooze)
    paused_until = cfg.get("paused_until")
    if paused_until:
        try:
            if isinstance(paused_until, (int, float)):
                pause_epoch = paused_until / 1000.0 if paused_until > 1e11 else float(paused_until)
                pause_dt = datetime.fromtimestamp(pause_epoch, tz=timezone.utc)
            elif isinstance(paused_until, str):
                pause_dt = datetime.fromisoformat(paused_until.replace("Z", "+00:00"))
            else:
                pause_dt = paused_until

            if now.astimezone(timezone.utc) < pause_dt.astimezone(timezone.utc):
                pause_str = pause_dt.astimezone(TIMEZONE).strftime("%H:%M")
                logger.info(f"Notifications en pause jusqu'à {pause_str}.")
                return False, f"Notifications en pause jusqu'à {pause_str}", []
        except Exception as e:
            logger.warning(f"Erreur lors de la vérification de paused_until ({paused_until}): {e}")

    active_directions: List[str] = []

    # 3. Vérification de la surveillance ponctuelle (Widget ou Déclencheur retour)
    # A. Surveillance ponctuelle par Widget / Géolocalisation (quick_monitoring_until)
    quick_until = cfg.get("quick_monitoring_until")
    quick_dir = cfg.get("quick_monitoring_direction", "AUTO")
    if quick_until:
        try:
            if isinstance(quick_until, (int, float)):
                q_epoch = quick_until / 1000.0 if quick_until > 1e11 else float(quick_until)
                q_dt = datetime.fromtimestamp(q_epoch, tz=timezone.utc)
            elif isinstance(quick_until, str):
                q_dt = datetime.fromisoformat(quick_until.replace("Z", "+00:00"))
            else:
                q_dt = quick_until

            if now.astimezone(timezone.utc) < q_dt.astimezone(timezone.utc):
                target_dirs = ["TO_PARIS", "TO_MEUDON"] if quick_dir in ("AUTO", None, "") else [quick_dir]
                for td in target_dirs:
                    if td not in active_directions:
                        active_directions.append(td)
                q_str = q_dt.astimezone(TIMEZONE).strftime("%H:%M")
                logger.info(f"Surveillance ponctuelle (Widget) active jusqu'à {q_str} pour {quick_dir}.")
        except Exception as e:
            logger.warning(f"Erreur vérification quick_monitoring_until: {e}")

    # B. Rétrocompatibilité return_commute_until (Paris ➔ Meudon)
    return_until = cfg.get("return_commute_until")
    if return_until:
        try:
            if isinstance(return_until, (int, float)):
                ret_epoch = return_until / 1000.0 if return_until > 1e11 else float(return_until)
                ret_dt = datetime.fromtimestamp(ret_epoch, tz=timezone.utc)
            elif isinstance(return_until, str):
                ret_dt = datetime.fromisoformat(return_until.replace("Z", "+00:00"))
            else:
                ret_dt = return_until

            if now.astimezone(timezone.utc) < ret_dt.astimezone(timezone.utc):
                if "TO_MEUDON" not in active_directions:
                    active_directions.append("TO_MEUDON")
                ret_str = ret_dt.astimezone(TIMEZONE).strftime("%H:%M")
                logger.info(f"Surveillance retour travail active jusqu'à {ret_str}.")
        except Exception as e:
            logger.warning(f"Erreur vérification return_commute_until: {e}")

    # 4. Vérification des jours actifs et plages horaires programmées
    active_days = cfg.get("active_days")
    if active_days is None:
        active_days = ACTIVE_DAYS

    if current_day in active_days:
        # A. Plage Matin (Meudon ➔ Paris-Montparnasse)
        m_enabled = cfg.get("morning_enabled", True)
        m_start_h = cfg.get("morning_start_hour", cfg.get("start_hour", START_HOUR))
        m_start_m = cfg.get("morning_start_minute", cfg.get("start_minute", START_MINUTE))
        m_end_h = cfg.get("morning_end_hour", cfg.get("end_hour", END_HOUR))
        m_end_m = cfg.get("morning_end_minute", cfg.get("end_minute", END_MINUTE))

        w_morning_start = time(int(m_start_h), int(m_start_m))
        w_morning_end = time(int(m_end_h), int(m_end_m))

        if m_enabled and (w_morning_start <= current_time <= w_morning_end):
            if "TO_PARIS" not in active_directions:
                active_directions.append("TO_PARIS")

        # B. Plage Soir (Paris-Montparnasse ➔ Meudon)
        e_enabled = cfg.get("evening_enabled", True)
        e_start_h = cfg.get("evening_start_hour", EVENING_START_HOUR)
        e_start_m = cfg.get("evening_start_minute", EVENING_START_MINUTE)
        e_end_h = cfg.get("evening_end_hour", EVENING_END_HOUR)
        e_end_m = cfg.get("evening_end_minute", EVENING_END_MINUTE)

        w_evening_start = time(int(e_start_h), int(e_start_m))
        w_evening_end = time(int(e_end_h), int(e_end_m))

        if e_enabled and (w_evening_start <= current_time <= w_evening_end):
            if "TO_MEUDON" not in active_directions:
                active_directions.append("TO_MEUDON")

    if not active_directions:
        day_name = now.strftime('%A')
        return False, f"Aucun trajet en cours de surveillance ({day_name}, {current_time.strftime('%H:%M')})", []

    # 5. Vérification de l'intervalle de fréquence
    freq_min = int(cfg.get("frequency_minutes", 3))
    last_check = cfg.get("last_check_timestamp")
    if last_check and freq_min > 0:
        elapsed_sec = now.timestamp() - float(last_check)
        min_interval_sec = (freq_min * 60) - 20  # Tolérance de 20s
        if elapsed_sec < min_interval_sec:
            remaining = int(min_interval_sec - elapsed_sec)
            return False, f"Fréquence de {freq_min} min respectée (prochain appel dans ~{remaining}s)", active_directions

    dir_desc = " & ".join(["Meudon ➔ Paris" if d == "TO_PARIS" else "Paris ➔ Meudon" for d in active_directions])
    return True, f"Surveillance active ({dir_desc})", active_directions


def fetch_stop_monitoring(
    monitoring_ref: str = MONITORING_REF,
    api_key: str = PRIM_API_KEY,
    line_ref: Optional[str] = LINE_REF,
) -> Dict[str, Any]:
    """
    Interroge l'API PRIM SIRI Lite (stop-monitoring) pour un point d'arrêt donné.
    """
    if not api_key:
        raise ValueError(
            "PRIM_API_KEY est manquante. Veuillez définir la variable d'environnement PRIM_API_KEY."
        )

    headers = {
        "apiKey": api_key,
        "Accept": "application/json",
    }
    params: Dict[str, str] = {
        "MonitoringRef": monitoring_ref,
    }
    response = None
    try:
        response = requests.get(PRIM_API_URL, headers=headers, params=params, timeout=10)
        from firestore_client import record_prim_call_stat

        record_prim_call_stat(response.status_code)
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        if response is None:
            try:
                from firestore_client import record_prim_call_stat

                record_prim_call_stat(500)
            except Exception:
                pass
        raise


def is_train_cancelled(call: Dict[str, Any], journey: Dict[str, Any]) -> bool:
    """
    Détecte si un train ou l'arrêt de ce train est supprimé/annulé.
    """
    # Vérification du statut de départ/arrivée dans MonitoredCall
    dep_status = str(call.get("DepartureStatus", "")).lower()
    arr_status = str(call.get("ArrivalStatus", "")).lower()
    cancelled_statuses = {"cancelled", "supprime", "supprimé", "cancelledstop"}

    if dep_status in cancelled_statuses or arr_status in cancelled_statuses:
        return True

    # Vérification des drapeaux d'annulation SIRI
    if call.get("Cancellation") is True or journey.get("Cancellation") is True:
        return True

    return False


def parse_aimed_time(iso_str: Optional[str]) -> str:
    """Formate une date ISO en heure locale HH:MM."""
    if not iso_str:
        return "--:--"
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        local_dt = dt.astimezone(TIMEZONE)
        return local_dt.strftime("%H:%M")
    except Exception:
        return iso_str[-8:-3] if len(iso_str) >= 8 else iso_str


def extract_disrupted_trains(
    data: Dict[str, Any],
    active_directions: Optional[List[str]] = None,
    include_delays: bool = True,
    min_delay_minutes: int = MIN_DELAY_MINUTES,
) -> List[Dict[str, Any]]:
    """
    Parcourt la réponse JSON SIRI Lite et extrait les anomalies de circulation :
    1. Trains annulés (status = "ANNULÉ", status_code = "CANCELLED")
    2. Trains retardés si include_delays=True et delay >= min_delay_minutes
       (status = "RETARDÉ (+X min)", status_code = "DELAYED")
    correspondant aux directions actives ("TO_PARIS" et/ou "TO_MEUDON").
    """
    disrupted_trains = []
    directions_to_check = active_directions if active_directions is not None else ["TO_PARIS", "TO_MEUDON"]

    try:
        siri = data.get("Siri", {})
        service_delivery = siri.get("ServiceDelivery", {})
        stop_deliveries = service_delivery.get("StopMonitoringDelivery", [])

        if not stop_deliveries:
            return []

        visits = stop_deliveries[0].get("MonitoredStopVisit", [])

        for visit in visits:
            journey = visit.get("MonitoredVehicleJourney", {})
            call = journey.get("MonitoredCall", {})

            # Destination & Direction
            destination_names = [
                d.get("value", "") for d in journey.get("DestinationName", [])
            ]
            destination_str = " ".join(destination_names)

            dir_ref = journey.get("DirectionRef", {}).get("value", "")
            is_to_paris = (dir_ref == "Retour") or ("montparnasse" in destination_str.lower()) or ("paris" in destination_str.lower())
            train_dir = "TO_PARIS" if is_to_paris else "TO_MEUDON"

            if train_dir not in directions_to_check:
                continue

            aimed_dep_iso = call.get("AimedDepartureTime") or call.get("AimedArrivalTime")
            expected_dep_iso = call.get("ExpectedDepartureTime") or call.get("ExpectedArrivalTime")
            dep_status_raw = call.get("DepartureStatus", "")
            cancelled = is_train_cancelled(call, journey)

            status, status_label, delay_min = compute_delay_and_status(
                aimed_dep_iso, expected_dep_iso, cancelled, dep_status_raw
            )

            is_delayed = include_delays and (status == "DELAYED" or delay_min >= min_delay_minutes)
            if not cancelled and not is_delayed:
                continue

            journey_ref = (
                journey.get("VehicleJourneyRef", {}).get("value")
                or journey.get("FramedVehicleJourneyRef", {}).get("DatedVehicleJourneyRef")
                or visit.get("ItemIdentifier", "")
            )

            # Code mission (ex. ROPO, POMA, etc.)
            notes = [n.get("value", "") for n in journey.get("JourneyNote", [])]
            mission_code = notes[0] if notes else ""
            if not mission_code and journey_ref:
                mission_code = str(journey_ref).split(":")[-1]

            dep_time_display = parse_aimed_time(aimed_dep_iso)
            expected_time_display = parse_aimed_time(expected_dep_iso) if expected_dep_iso else dep_time_display

            status_code = "CANCELLED" if cancelled else "DELAYED"
            status_text = "ANNULÉ" if cancelled else f"RETARDÉ ({status_label})"

            today_str = datetime.now(TIMEZONE).strftime("%Y-%m-%d")
            # Identifiant unique de déduplication : inclut status_code
            train_id = f"{today_str}_{train_dir}_{journey_ref or mission_code}_{dep_time_display}_{status_code}"

            dir_label = "Meudon ➔ Paris-Montparnasse" if is_to_paris else "Paris-Montparnasse ➔ Meudon"
            stop_display = "Meudon" if is_to_paris else "Paris-Montparnasse"

            disrupted_trains.append({
                "id": train_id,
                "journey_ref": journey_ref,
                "mission_code": mission_code,
                "departure_time": dep_time_display,
                "expected_time": expected_time_display,
                "aimed_time_iso": aimed_dep_iso,
                "destination": destination_str or ("Paris-Montparnasse" if is_to_paris else "Meudon"),
                "direction_code": train_dir,
                "direction_label": dir_label,
                "stop_name": stop_display,
                "status": status_text,
                "status_code": status_code,
                "delay_minutes": delay_min,
                "is_cancelled": cancelled,
            })

    except Exception as e:
        logger.error(f"Erreur lors du parsing des perturbations: {e}", exc_info=True)

    return disrupted_trains


def extract_cancelled_trains(
    data: Dict[str, Any],
    active_directions: Optional[List[str]] = None,
) -> List[Dict[str, Any]]:
    """Rétrocompatibilité : extrait uniquement les trains annulés."""
    return extract_disrupted_trains(data, active_directions=active_directions, include_delays=False)


def compute_delay_and_status(
    aimed_iso: Optional[str],
    expected_iso: Optional[str],
    is_cancelled: bool,
    dep_status_raw: str = "",
) -> Tuple[str, str, int]:
    """
    Retourne (status, status_label, delay_minutes).
    status: 'CANCELLED', 'DELAYED', 'ON_TIME'
    """
    if is_cancelled:
        return "CANCELLED", "Supprimé", 0

    if not aimed_iso or not expected_iso:
        if "delayed" in dep_status_raw.lower():
            return "DELAYED", "Retardé", 0
        return "ON_TIME", "À l'heure", 0

    try:
        aimed_dt = datetime.fromisoformat(aimed_iso.replace("Z", "+00:00"))
        exp_dt = datetime.fromisoformat(expected_iso.replace("Z", "+00:00"))
        delay_sec = (exp_dt - aimed_dt).total_seconds()
        delay_min = int(round(delay_sec / 60.0))

        if delay_min >= 2:
            return "DELAYED", f"+{delay_min} min", delay_min
        elif delay_min <= -2:
            return "ON_TIME", f"-{abs(delay_min)} min", delay_min
        else:
            return "ON_TIME", "À l'heure", 0
    except Exception:
        return "ON_TIME", "À l'heure", 0


def extract_all_departures(
    data: Dict[str, Any],
    destination_filter: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """
    Extrait l'ensemble des prochains passages en gare (à l'heure, retardés ou supprimés).
    Permet de consulter le tableau complet des départs en temps réel.
    """
    departures = []

    try:
        siri = data.get("Siri", {})
        service_delivery = siri.get("ServiceDelivery", {})
        stop_deliveries = service_delivery.get("StopMonitoringDelivery", [])

        if not stop_deliveries:
            return []

        visits = stop_deliveries[0].get("MonitoredStopVisit", [])

        for visit in visits:
            journey = visit.get("MonitoredVehicleJourney", {})
            call = journey.get("MonitoredCall", {})

            # Destination
            destination_names = [
                d.get("value", "") for d in journey.get("DestinationName", [])
            ]
            dest_str = " ".join(destination_names)

            if destination_filter and destination_filter.lower() not in dest_str.lower():
                continue

            # Code mission
            notes = [n.get("value", "") for n in journey.get("JourneyNote", [])]
            mission_code = notes[0] if notes else ""
            journey_ref = (
                journey.get("VehicleJourneyRef", {}).get("value")
                or journey.get("FramedVehicleJourneyRef", {}).get("DatedVehicleJourneyRef")
                or visit.get("ItemIdentifier", "")
            )
            if not mission_code and journey_ref:
                mission_code = str(journey_ref).split(":")[-1]

            # Voie / Quai
            platform = (
                call.get("DeparturePlatformName", {}).get("value")
                or call.get("ArrivalPlatformName", {}).get("value")
                or ""
            )

            aimed_iso = call.get("AimedDepartureTime") or call.get("AimedArrivalTime")
            expected_iso = call.get("ExpectedDepartureTime") or call.get("ExpectedArrivalTime")

            cancelled = is_train_cancelled(call, journey)
            raw_status = call.get("DepartureStatus", "")
            status, status_label, delay_min = compute_delay_and_status(
                aimed_iso, expected_iso, cancelled, raw_status
            )

            aimed_time = parse_aimed_time(aimed_iso)
            expected_time = parse_aimed_time(expected_iso) if expected_iso else aimed_time

            # Direction : Vers Paris ou Vers Banlieue
            is_paris = "montparnasse" in dest_str.lower() or "paris" in dest_str.lower()
            direction = "Paris-Montparnasse" if is_paris else "Banlieue"

            unique_id = f"{journey_ref or mission_code}_{aimed_time}_{dest_str}"

            departures.append({
                "id": unique_id,
                "mission_code": mission_code,
                "destination": dest_str or "Paris-Montparnasse",
                "direction": direction,
                "aimed_time": aimed_time,
                "expected_time": expected_time,
                "platform": platform,
                "status": status,  # "ON_TIME", "DELAYED", "CANCELLED"
                "status_label": status_label,
                "delay_minutes": delay_min,
                "is_cancelled": cancelled,
            })

    except Exception as e:
        logger.error(f"Erreur lors de l'extraction de tous les départs: {e}", exc_info=True)

    # Tri par heure prévue de départ
    departures.sort(key=lambda d: d.get("aimed_time", "99:99"))
    return departures
