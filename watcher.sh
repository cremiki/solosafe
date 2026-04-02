#!/bin/bash
# Monitora Android build errors e li manda al server
# Lancio DAL MAC: ./watcher.sh ~/Projects/solosafe-android-v3

PROJECT_DIR="${1:-$HOME/Projects/solosafe-android-v3}"
SERVER="root@46.224.181.59"
ERRORS_FILE="/opt/solosafe/errors.log"
SRC_DIR="$PROJECT_DIR/app/src"

# Java/Android SDK
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

echo "👀 SoloSafe Watcher avviato"
echo "Progetto: $PROJECT_DIR"
echo "Server: $SERVER"
echo "Monitoraggio: $SRC_DIR (solo .kt e .xml)"

LAST_HASH=""

wait_for_changes() {
    # Calcola hash dei file sorgente
    local new_hash
    new_hash=$(find "$SRC_DIR" -name "*.kt" -o -name "*.xml" 2>/dev/null | sort | xargs cat 2>/dev/null | md5 2>/dev/null || echo "")

    if [ "$new_hash" = "$LAST_HASH" ] && [ -n "$LAST_HASH" ]; then
        return 1  # nessun cambiamento
    fi
    LAST_HASH="$new_hash"
    return 0  # cambiato
}

send_errors() {
    echo "$1" | ssh -o ConnectTimeout=5 $SERVER "cat > $ERRORS_FILE"
    echo "[$(date +%H:%M:%S)] Errori inviati al server"
}

# Build iniziale
cd "$PROJECT_DIR" || exit 1
echo "[$(date +%H:%M:%S)] Build iniziale..."
BUILD_OUTPUT=$(./gradlew assembleDebug 2>&1)
if [ $? -ne 0 ]; then
    echo "❌ Build iniziale fallito"
    ERRORS=$(echo "$BUILD_OUTPUT" | tail -80)
    send_errors "BUILD_FAILED at $(date)
$ERRORS"
else
    echo "✅ Build iniziale OK"
    LAST_HASH=$(find "$SRC_DIR" -name "*.kt" -o -name "*.xml" 2>/dev/null | sort | xargs cat 2>/dev/null | md5 2>/dev/null || echo "")
    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK" ]; then
        adb -s BV6600EEA0046274 install -r "$APK" 2>/dev/null && echo "📱 APK installato su BV6600"
    fi
fi

# Loop: controlla cambiamenti ogni 10 secondi
echo "[$(date +%H:%M:%S)] In ascolto per modifiche ai sorgenti..."
while true; do
    sleep 10

    if ! wait_for_changes; then
        continue  # nessun cambiamento, skip
    fi

    echo ""
    echo "[$(date +%H:%M:%S)] Sorgenti modificati — rebuild..."
    BUILD_OUTPUT=$(./gradlew assembleDebug 2>&1)

    if [ $? -ne 0 ]; then
        echo "❌ Build fallito — invio errori al server"
        ERRORS=$(echo "$BUILD_OUTPUT" | tail -80)
        send_errors "BUILD_FAILED at $(date)
$ERRORS"
    else
        echo "✅ Build OK"
        # Svuota errors.log (nessun errore)
        ssh -o ConnectTimeout=5 $SERVER "echo '' > $ERRORS_FILE" 2>/dev/null
        APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        if [ -f "$APK" ]; then
            adb -s BV6600EEA0046274 install -r "$APK" 2>/dev/null && echo "📱 APK installato su BV6600"
        fi
    fi
done
