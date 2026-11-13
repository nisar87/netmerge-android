#!/bin/sh
# Fetches and decrypts the diagnostics reports: diagnostics/read.sh PRIVATE_KEY.pem [DAYS] -> reports.jsonl here.
# Starts the Diagnostics workflow, waits for it, fetches the encrypted reports from the diagnostics-data branch and
# decrypts them with the private key. Run inside a clone of the repository.
set -e
KEY=$1; DAYS=${2:-2}; R=nisar87/netmerge-android; OUT=${OUT:-$PWD/reports.jsonl}
[ -f "$KEY" ] || { echo "usage: $0 private-key.pem [days]"; exit 1; }
before=$(gh api "repos/$R/actions/workflows/diagnostics.yml/runs?per_page=1" -q '.workflow_runs[0].id // 0')
gh api -X POST "repos/$R/actions/workflows/diagnostics.yml/dispatches" -f ref=main -f "inputs[days]=$DAYS"
run=$before
while [ "$run" = "$before" ]; do sleep 5; run=$(gh api "repos/$R/actions/workflows/diagnostics.yml/runs?per_page=1" -q '.workflow_runs[0].id'); done
until [ "$(gh api repos/$R/actions/runs/$run -q .status)" = completed ]; do sleep 10; done
[ "$(gh api repos/$R/actions/runs/$run -q .conclusion)" = success ] || { echo "workflow run $run failed"; exit 1; }
D=$(mktemp -d)
git fetch -q origin diagnostics-data
for f in count.txt key.enc reports.enc; do git show FETCH_HEAD:$f > "$D/$f"; done
cat "$D/count.txt"
openssl pkeyutl -decrypt -inkey "$KEY" -pkeyopt rsa_padding_mode:oaep -in "$D/key.enc" -out "$D/k.bin"
openssl enc -d -aes-256-cbc -pbkdf2 -pass file:"$D/k.bin" -in "$D/reports.enc" | gunzip > "$OUT"
rm -rf "$D"
echo "$OUT: $(wc -l < "$OUT") reports"
