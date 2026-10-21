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

import os
from datetime import time
from zoneinfo import ZoneInfo
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    # Lecture basique de .env si python-dotenv n'est pas installé
    if os.path.exists(".env"):
        with open(".env") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())

# --- PRIM IDFM Configuration ---
# Clé API PRIM (à obtenir gratuitement sur https://prim.iledefrance-mobilites.fr/)
PRIM_API_KEY = os.getenv("PRIM_API_KEY", "")
PRIM_API_URL = os.getenv(
    "PRIM_API_URL",
    "https://prim.iledefrance-mobilites.fr/marketplace/stop-monitoring"
)

# Gare de Meudon (Code StopArea IDFM)
# STIF:StopArea:SP:43162: correspond à la gare de Meudon (Ligne N)
DEFAULT_MONITORING_REF = "STIF:StopArea:SP:43162:"
MONITORING_REF = os.getenv("PRIM_MONITORING_REF", DEFAULT_MONITORING_REF)

# Gare de Paris-Montparnasse (Code StopArea IDFM)
# STIF:StopArea:SP:43238: correspond à la gare de Paris-Montparnasse (Ligne N)
DEFAULT_PARIS_MONITORING_REF = "STIF:StopArea:SP:43238:"
PARIS_MONITORING_REF = os.getenv("PRIM_PARIS_MONITORING_REF", DEFAULT_PARIS_MONITORING_REF)

# Ligne N (Code IDFM STIF:Line::C01736:)
LINE_REF = os.getenv("PRIM_LINE_REF", "STIF:Line::C01736:")

# Filtre de destination : on cherche les trains à destination de Paris-Montparnasse
DESTINATION_FILTER = os.getenv("DESTINATION_FILTER", "Montparnasse")

# --- Horaires et Jours de surveillance ---
# Jours actifs : 0=Lundi, 1=Mardi, 2=Mercredi, 3=Jeudi, 4=Vendredi, 5=Samedi, 6=Dimanche
ACTIVE_DAYS = [int(d.strip()) for d in os.getenv("ACTIVE_DAYS", "0,1,2,3,4").split(",")]

# Plage Matin par défaut (Aller : Meudon -> Paris) : 07h00 à 09h30
TIMEZONE = ZoneInfo(os.getenv("TIMEZONE", "Europe/Paris"))
START_HOUR = int(os.getenv("START_HOUR", "7"))
START_MINUTE = int(os.getenv("START_MINUTE", "0"))
END_HOUR = int(os.getenv("END_HOUR", "9"))
END_MINUTE = int(os.getenv("END_MINUTE", "30"))

# Plage Soir par défaut (Retour : Paris -> Meudon) : 17h00 à 19h30
EVENING_START_HOUR = int(os.getenv("EVENING_START_HOUR", "17"))
EVENING_START_MINUTE = int(os.getenv("EVENING_START_MINUTE", "0"))
EVENING_END_HOUR = int(os.getenv("EVENING_END_HOUR", "19"))
EVENING_END_MINUTE = int(os.getenv("EVENING_END_MINUTE", "30"))

WINDOW_START = time(START_HOUR, START_MINUTE)
WINDOW_END = time(END_HOUR, END_MINUTE)

# Option pour forcer l'exécution hors plage horaire (utile pour les tests et démos)
FORCE_CHECK = os.getenv("FORCE_CHECK", "false").lower() in ("true", "1", "yes")

# Fréquence par défaut : 3 minutes
DEFAULT_FREQUENCY_MINUTES = int(os.getenv("DEFAULT_FREQUENCY_MINUTES", "3"))

# --- Détection des retards & Surveillance ponctuelle (Widget) ---
NOTIFY_DELAYS = os.getenv("NOTIFY_DELAYS", "true").lower() in ("true", "1", "yes")
MIN_DELAY_MINUTES = int(os.getenv("MIN_DELAY_MINUTES", "5"))
DEFAULT_QUICK_DURATION_MINUTES = int(os.getenv("DEFAULT_QUICK_DURATION_MINUTES", "60"))

# TTL de cache pour la consultation des départs (Méthode 2 On-Demand : 90 secondes)
DEPARTURES_CACHE_TTL_SECONDS = int(os.getenv("DEPARTURES_CACHE_TTL_SECONDS", "90"))

# --- Firebase & Firestore Configuration ---
FCM_TOPIC = os.getenv("FCM_TOPIC", "trains_meudon_montparnasse")
FIRESTORE_COLLECTION = os.getenv("FIRESTORE_COLLECTION", "notified_trains")
SETTINGS_COLLECTION = os.getenv("SETTINGS_COLLECTION", "settings")
SCHEDULE_DOCUMENT = os.getenv("SCHEDULE_DOCUMENT", "monitoring_schedule")
FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID", "")

# --- Sécurité & Authentification des Endpoints ---
# Clé secrète pour les opérations administratives et simulations (/simulate-alert)
ADMIN_SECRET_KEY = os.getenv("ADMIN_SECRET_KEY", "")
# Clé secrète d'application optionnelle (transmise dans le header X-API-Key)
BACKEND_API_KEY = os.getenv("BACKEND_API_KEY", "")
# Autoriser ou désactiver l'endpoint de simulation en production
ALLOW_SIMULATION_ENDPOINT = os.getenv("ALLOW_SIMULATION_ENDPOINT", "true").lower() in ("true", "1", "yes")
# Exiger un jeton Firebase App Check valide (Play Integrity) pour les requêtes mobiles
APP_CHECK_ENFORCED = os.getenv("APP_CHECK_ENFORCED", "false").lower() in ("true", "1", "yes")
