#!/bin/sh
# Fetches and decrypts the diagnostics reports: diagnostics/read.sh PRIVATE_KEY.pem [DAYS] -> reports.jsonl here.
# Starts the Diagnostics workflow, waits for it, downloads its artifact and decrypts it with the private key.
set -e
KEY=$1; DAYS=${2:-2}; R=nisar87/netmerge-android
[ -f "$KEY" ] || { echo "usage: $0 private-key.pem [days]"; exit 1; }
before=$(gh api "repos/$R/actions/workflows/diagnostics.yml/runs?per_page=1" -q '.workflow_runs[0].id // 0')
gh api -X POST "repos/$R/actions/workflows/diagnostics.yml/dispatches" -f ref=main -f "inputs[days]=$DAYS"
run=$before
while [ "$run" = "$before" ]; do sleep 5; run=$(gh api "repos/$R/actions/workflows/diagnostics.yml/runs?per_page=1" -q '.workflow_runs[0].id'); done
until [ "$(gh api repos/$R/actions/runs/$run -q .status)" = completed ]; do sleep 10; done
[ "$(gh api repos/$R/actions/runs/$run -q .conclusion)" = success ] || { echo "workflow run $run failed"; exit 1; }
D=$(mktemp -d)
gh run download "$run" -R $R -n reports -D "$D"
cat "$D/count.txt"
openssl pkeyutl -decrypt -inkey "$KEY" -pkeyopt rsa_padding_mode:oaep -in "$D/key.enc" -out "$D/k.bin"
openssl enc -d -aes-256-cbc -pbkdf2 -pass file:"$D/k.bin" -in "$D/reports.enc" | gunzip > reports.jsonl
rm -rf "$D"
echo "reports.jsonl: $(wc -l < reports.jsonl) reports"
