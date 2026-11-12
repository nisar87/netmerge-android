#!/bin/sh
# One-time setup of the Firebase Test Lab project for CI, run in Google Cloud Shell by the project's owner.
# Creates a robot account that may only run tests in this project, and lets only this GitHub repository use it
# (Workload Identity Federation): no key file is created anywhere.
set -e
P=netmerge-tests
REPO=nisar87/netmerge-android
SA=netmerge-ci@$P.iam.gserviceaccount.com
gcloud services enable testing.googleapis.com toolresults.googleapis.com iam.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com --project $P
gcloud iam service-accounts describe $SA --project $P >/dev/null 2>&1 ||
  gcloud iam service-accounts create netmerge-ci --display-name "NetMerge CI (Test Lab only)" --project $P
# Test Lab runs and their results (results go to a Cloud Storage bucket Test Lab makes in this project)
for role in roles/cloudtestservice.testAdmin roles/firebase.analyticsViewer roles/storage.admin roles/serviceusage.serviceUsageConsumer; do
  gcloud projects add-iam-policy-binding $P --member serviceAccount:$SA --role $role --condition None >/dev/null
done
gcloud iam workload-identity-pools describe github --location global --project $P >/dev/null 2>&1 ||
  gcloud iam workload-identity-pools create github --location global --display-name GitHub --project $P
gcloud iam workload-identity-pools providers describe netmerge-android --location global --workload-identity-pool github --project $P >/dev/null 2>&1 ||
  gcloud iam workload-identity-pools providers create-oidc netmerge-android --location global \
    --workload-identity-pool github --issuer-uri https://token.actions.githubusercontent.com \
    --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition "assertion.repository=='$REPO'" --project $P
PN=$(gcloud projects describe $P --format 'value(projectNumber)')
gcloud iam service-accounts add-iam-policy-binding $SA --project $P --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/$PN/locations/global/workloadIdentityPools/github/attribute.repository/$REPO" >/dev/null
echo "SETUP-DONE provider=projects/$PN/locations/global/workloadIdentityPools/github/providers/netmerge-android sa=$SA"
