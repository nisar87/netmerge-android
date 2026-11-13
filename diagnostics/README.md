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
