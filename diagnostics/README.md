# Diagnostics database

Firebase project `netmerge-app` (Spark, no cost), Cloud Firestore `(default)` in asia-south1, set up on 13 November
2026 in the Firebase console:

- the rules in `firestore.rules` (published in Firestore > Rules): reports may only be added, with the fields and
  sizes listed there; nothing can be read, changed or deleted from the app;
- the web app "NetMerge Android diagnostics" gives the API key in `Diagnostics.kt`. A Firebase key is not a secret: it
  only names the project, and the rules decide what it may do.

Reading the reports: Firebase console > Firestore > Data > `reports`. Each has `install` (random per installation),
`kind` (`log` or `crash`), `app`, `code`, `device`, `android`, `settings`, `engine`, `log`, `crash`, `at`.

Checked from a browser: adding a report gives 200; reading, an unknown field or another `kind` gives 403.

## Who reads them

Nobody has to open the console: the **Diagnostics** workflow (`.github/workflows/diagnostics.yml`, daily and by hand)
reads the reports with the CI service account (`netmerge-ci@netmerge-tests`, Cloud Datastore Viewer on `netmerge-app`,
no key file) and keeps them as an artifact for 7 days, **encrypted** with `reports-public.pem`: this repository is
public, and its workflow log shows only counts. A scheduled Claude task holds the private key, runs
`diagnostics/read.sh key.pem [days]` each day, looks for bugs in the reports, fixes them and releases a new version.
