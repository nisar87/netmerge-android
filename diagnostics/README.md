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

Nobody has to open the console: `diagnostics/read.sh [days]`, run by a daily scheduled Claude task, makes a new key
pair, starts the **Diagnostics** workflow (`.github/workflows/diagnostics.yml`) with the public half, and the workflow
reads the reports with the CI service account (`netmerge-ci@netmerge-tests`, Cloud Datastore Viewer on `netmerge-app`,
no key file), encrypts them with that key and leaves them on the branch `diagnostics-data` (one commit, replaced each
run). This repository is public: its workflow log shows only counts, and no lasting key exists that opens a run (the
private half lives only while read.sh runs). The task then looks for bugs in the reports, fixes them and releases a new
version.
