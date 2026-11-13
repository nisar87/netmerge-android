#!/bin/sh
# One-time setup of the diagnostics database (Firebase project netmerge-app), run in Google Cloud Shell by its owner:
# a Firestore database where the app may only add reports, and an API key limited to Firestore for the app to use.
set -e
P=netmerge-app
gcloud services enable firestore.googleapis.com firebaserules.googleapis.com apikeys.googleapis.com --project $P
gcloud firestore databases describe --database='(default)' --project $P >/dev/null 2>&1 ||
  gcloud firestore databases create --project $P --location=asia-south1 --type=firestore-native
D=$(mktemp -d)
curl -sL https://raw.githubusercontent.com/nisar87/netmerge-android/diagnostics/diagnostics/firestore.rules -o $D/firestore.rules
printf '{"firestore":{"rules":"firestore.rules"}}' > $D/firebase.json
(cd $D && firebase deploy --only firestore:rules --project $P --non-interactive)
K=$(gcloud services api-keys list --project $P --filter="displayName='NetMerge app diagnostics'" --format='value(name)' | head -1)
[ -n "$K" ] || { gcloud services api-keys create --project $P --display-name "NetMerge app diagnostics" \
  --api-target=service=firestore.googleapis.com >/dev/null 2>&1; sleep 5;
  K=$(gcloud services api-keys list --project $P --filter="displayName='NetMerge app diagnostics'" --format='value(name)' | head -1); }
echo "DIAG-DONE key=$(gcloud services api-keys get-key-string $K --format='value(keyString)')"
