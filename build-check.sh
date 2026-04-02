#!/bin/bash
# Esegui DAL MAC nella cartella del progetto Android
# Invia il risultato del build al server per analisi
#
# Uso: ./build-check.sh

echo "=== SoloSafe Build Check ==="
echo "Timestamp: $(date)"
echo ""

cd "$(dirname "$0")" 2>/dev/null || cd ~/Projects/solosafe-android-v3

echo "--- Gradle assembleDebug ---"
./gradlew assembleDebug 2>&1 | tail -80 > /tmp/solosafe-build.txt

if [ $? -eq 0 ]; then
    echo "BUILD SUCCESS" | tee -a /tmp/solosafe-build.txt
else
    echo "BUILD FAILED" | tee -a /tmp/solosafe-build.txt
fi

echo ""
echo "--- Uploading to server ---"
scp /tmp/solosafe-build.txt root@46.224.181.59:/opt/solosafe/build-errors.txt

echo "Done. Errors available at /opt/solosafe/build-errors.txt"
