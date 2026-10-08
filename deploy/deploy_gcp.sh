#!/usr/bin/env bash

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

set -e

# Configuration par défaut
PROJECT_ID=${GCP_PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}
if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" = "(unset)" ]; then
    PROJECT_ID="alexismp-runner"
fi
REGION=${GCP_REGION:-"europe-west1"}
SERVICE_NAME="mon-trajet-monitor"
SCHEDULER_JOB_NAME="mon-trajet-scheduler"

echo "=========================================================="
echo " 🚀 Déploiement de Mon trajet (Cloud Run functions) sur GCP"
echo "=========================================================="
echo "Projet GCP : ${PROJECT_ID}"
echo "Région     : ${REGION}"
echo "Service    : ${SERVICE_NAME}"
echo "Technologie: Cloud Run functions (Gen2)"
echo "=========================================================="

if [ -z "$PRIM_API_KEY" ] && [ -f "./backend/.env" ]; then
    PRIM_API_KEY=$(grep '^PRIM_API_KEY=' ./backend/.env | cut -d '=' -f2- | tr -d ' "'\''')
fi

if [ -z "$PRIM_API_KEY" ]; then
    echo "⚠️  Attention : La variable d'environnement PRIM_API_KEY n'est pas définie."
    read -p "Veuillez saisir votre clé API IDFM PRIM : " PRIM_API_KEY
fi

# 1. Activation des APIs GCP nécessaires
echo "📦 Activation des APIs nécessaires (Cloud Functions, Run, Scheduler, Firestore, FCM)..."
gcloud services enable \
    cloudfunctions.googleapis.com \
    run.googleapis.com \
    cloudbuild.googleapis.com \
    cloudscheduler.googleapis.com \
    firestore.googleapis.com \
    fcm.googleapis.com \
    --project="${PROJECT_ID}"

# 2. Clé secrète d'administration pour /simulate-alert
ADMIN_SECRET_KEY=${ADMIN_SECRET_KEY:-$(grep '^ADMIN_SECRET_KEY=' ./backend/.env 2>/dev/null | cut -d '=' -f2- | tr -d ' "'\''')}
if [ -z "$ADMIN_SECRET_KEY" ]; then
    ADMIN_SECRET_KEY=$(openssl rand -hex 16 2>/dev/null || python3 -c "import secrets; print(secrets.token_hex(16))")
    echo "🔑 Génération automatique d'une clé secrète d'administration : ${ADMIN_SECRET_KEY}"
fi

# 3. Compte de service dédié pour Cloud Scheduler (Authentification OIDC)
SCHEDULER_SA_NAME="montrajet-scheduler-sa"
SCHEDULER_SA="${SCHEDULER_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

echo "🔐 Configuration du compte de service pour Cloud Scheduler (${SCHEDULER_SA})..."
if ! gcloud iam service-accounts describe "${SCHEDULER_SA}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    gcloud iam service-accounts create "${SCHEDULER_SA_NAME}" \
        --display-name="MonTrajet Cloud Scheduler Invoker" \
        --project="${PROJECT_ID}" || true
fi

# 4. Déploiement de la Cloud Function Gen2 avec plafond d'instances et variables de sécurité
echo "⚡ Déploiement de la fonction Cloud Functions Gen2 (max 2 instances)..."
gcloud functions deploy "${SERVICE_NAME}" \
    --gen2 \
    --runtime=python311 \
    --region="${REGION}" \
    --source=./backend \
    --entry-point=check_trains_http \
    --trigger-http \
    --max-instances=2 \
    --set-env-vars="PRIM_API_KEY=${PRIM_API_KEY},FIREBASE_PROJECT_ID=${PROJECT_ID},ADMIN_SECRET_KEY=${ADMIN_SECRET_KEY},ALLOW_SIMULATION_ENDPOINT=true" \
    --project="${PROJECT_ID}"

# Récupération de l'URL de la fonction
FUNCTION_URL=$(gcloud functions describe "${SERVICE_NAME}" --gen2 --region="${REGION}" --project="${PROJECT_ID}" --format="value(serviceConfig.uri)")
echo "✅ Cloud Function déployée avec succès : ${FUNCTION_URL}"

# Attribution du rôle Cloud Run Invoker au compte de service Scheduler
gcloud run services add-iam-policy-binding "${SERVICE_NAME}" \
    --region="${REGION}" \
    --member="serviceAccount:${SCHEDULER_SA}" \
    --role="roles/run.invoker" \
    --project="${PROJECT_ID}" >/dev/null 2>&1 || true

# 5. Création ou mise à jour du job Cloud Scheduler avec jeton OIDC
echo "⏰ Configuration du job Cloud Scheduler (avec authentification OIDC)..."
if gcloud scheduler jobs describe "${SCHEDULER_JOB_NAME}" --location="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    gcloud scheduler jobs update http "${SCHEDULER_JOB_NAME}" \
        --location="${REGION}" \
        --schedule="*/3 * * * *" \
        --time-zone="Europe/Paris" \
        --uri="${FUNCTION_URL}" \
        --http-method=GET \
        --oidc-service-account-email="${SCHEDULER_SA}" \
        --oidc-audience="${FUNCTION_URL}" \
        --project="${PROJECT_ID}"
    echo "✅ Job Scheduler mis à jour avec authentification OIDC."
else
    gcloud scheduler jobs create http "${SCHEDULER_JOB_NAME}" \
        --location="${REGION}" \
        --schedule="*/3 * * * *" \
        --time-zone="Europe/Paris" \
        --uri="${FUNCTION_URL}" \
        --http-method=GET \
        --oidc-service-account-email="${SCHEDULER_SA}" \
        --oidc-audience="${FUNCTION_URL}" \
        --project="${PROJECT_ID}"
    echo "✅ Job Scheduler créé avec authentification OIDC."
fi

echo "=========================================================="
echo "🎉 Déploiement terminé avec succès !"
echo "Pour tester immédiatement la fonction en direct :"
echo "  curl -s \"${FUNCTION_URL}?force=true\" | jq"
echo "=========================================================="
