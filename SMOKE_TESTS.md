# SMOKE_TESTS.md — Prova vivente del sistema

**Scopo**: queste sono le cose che DEVONO funzionare SEMPRE. Claude Code le verifica quando serve confermare lo stato (inizio sessione su richiesta dell'utente, o fine task per il componente toccato — vedi Regola 4 di `CLAUDE.md`).

Se una voce fallisce a inizio sessione, **segnalarlo all'utente prima di iniziare il lavoro**. Se fallisce a fine task, il task NON è completato.

**Ambiente di riferimento**: i test girano **sul Mac di Miki** (working directory locale del repo), salvo dove esplicitamente indicato "sul VPS" (via SSH).

---

## App Android (`~/Projects/solosafe/`)

### SMOKE-APP-01 — Il progetto compila in debug
- **Come verificare**: dalla root del repo Android, `./gradlew assembleDebug`
- **Output atteso**: termina con `BUILD SUCCESSFUL`, genera `app/build/outputs/apk/debug/app-debug.apk`
- **Se fallisce**: di solito è JDK mancante/sbagliato (serve JDK 17) o dipendenze Gradle non sincronizzate

### SMOKE-APP-02 — L'APK è installabile sul telefono collegato via USB
- **Come verificare**: con telefono collegato USB e debug USB abilitato, `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **Output atteso**: `Success`
- **Se fallisce**: verificare `adb devices` che mostri il telefono come "device" (non "unauthorized" o "offline")

### SMOKE-APP-03 — Il file `network_security_config.xml` consente il dominio del VPS
- **Come verificare**: `cat app/src/main/res/xml/network_security_config.xml`
- **Output atteso**: file presente, contiene `46.224.181.59` (o dominio di produzione configurato)

### SMOKE-APP-04 — I log dell'app girano e contengono "SoloSafe"
- **Come verificare** (solo se APK installato e app in esecuzione): `adb logcat -d -v time | grep -i "solosafe" | head -20`
- **Output atteso**: almeno una riga con tag `SoloSafe`. Se l'app non è in esecuzione, output vuoto (normale).

---

## Dashboard (`~/Projects/solosafe-dashboard/`)

### SMOKE-DASH-01 — La dashboard compila in produzione
- **Come verificare**: dalla root del repo dashboard, `npm run build`
- **Output atteso**: termina con exit code 0, niente errori, genera `build/`

### SMOKE-DASH-02 — La dashboard parte in dev mode senza errori
- **Come verificare**:
  ```
  npm start &
  sleep 20
  curl -s http://localhost:3000 | grep -o '<title>[^<]*</title>'
  ```
  Poi killare il processo: `pkill -f "react-scripts start"` oppure trovare il PID e `kill`.
- **Output atteso**: tag `<title>` presente e non vuoto

### SMOKE-DASH-03 — Nessuna credenziale hardcoded esposta nel codice
- **Come verificare**: dalla root del repo dashboard, `grep -rEn "AIzaSy|eyJhbGciOi.{40,}" src/`
- **Output atteso**: nessun match. Se ci sono match, quelle righe contengono credenziali che vanno spostate in variabili d'ambiente (`.env.local`).

---

## Backend alarm service (sul VPS `46.224.181.59`)

### SMOKE-BE-01 — Il servizio callcascade risponde sulla porta locale
- **Come verificare**: `ssh root@46.224.181.59 "curl -s http://localhost:3001/health"`
- **Output atteso**: JSON con `{"status":"ok", ...}` e codice HTTP 200

### SMOKE-BE-02 — Il servizio callcascade è raggiungibile via HTTPS da internet
- **Come verificare** (dal Mac): `curl -k -s https://46.224.181.59:8443/health`
- **Output atteso**: JSON con `{"status":"ok", ...}`
- **Se fallisce**: Nginx è giù, oppure il certificato SSL è scaduto, oppure il firewall blocca la porta 8443

### SMOKE-BE-03 — PM2 ha entrambi i servizi online
- **Come verificare**: `ssh root@46.224.181.59 "pm2 jlist"` e controllare che `solosafe-callcascade` e `solosafe-bot-telegram` siano entrambi `status: online`
- **Output atteso**: entrambi i servizi online, restart count basso

### SMOKE-BE-04 — Nessuna credenziale in chiaro nei file tracciati del repo backend
- **Come verificare** (quando il repo backend sarà stato creato): `grep -rEn "MESSAGENET_ACCOUNT_PASSWORD|TWILIO_AUTH_TOKEN|TELEGRAM_BOT_TOKEN" . --exclude-dir=node_modules --exclude-dir=.git`
- **Output atteso**: nessun match (le credenziali devono essere solo in `.env` non tracciato da Git)

---

## Database (Supabase)

### SMOKE-DB-01 — Le 7 tabelle principali esistono
- **Come verificare**: da Supabase SQL editor (manuale, Claude Code non ha accesso diretto):
  ```sql
  SELECT tablename FROM pg_tables WHERE schemaname='public'
  AND tablename IN ('operators','emergency_contacts','work_sessions','operator_status','alarm_events','alarm_event_log','app_config_log');
  ```
- **Output atteso**: 7 righe

### SMOKE-DB-02 — Le FK child → operators hanno ON DELETE CASCADE
- **Come verificare**: Supabase SQL editor:
  ```sql
  SELECT conname, confrelid::regclass AS parent, conrelid::regclass AS child, confdeltype
  FROM pg_constraint
  WHERE contype='f' AND confrelid='operators'::regclass;
  ```
- **Output atteso**: tutte le righe con `confdeltype = 'c'` (CASCADE)
- **STATO**: ❌ **NON ANCORA VERIFICATO**. Eseguire prima di qualsiasi operazione di cleanup.

---

## Credenziali e sicurezza

### SMOKE-SEC-01 — Credenziali Messagenet non scadute
- **STATO**: ⚠️ **PENDING** — le credenziali pianificate per rotazione 2026-04-25 sono ancora in chiaro. Da verificare dopo la rotazione.

### SMOKE-SEC-02 — Credenziali Twilio non scadute
- **STATO**: ⚠️ **PENDING** — idem come sopra.

### SMOKE-SEC-03 — Token Telegram Bot non revocato
- **STATO**: ⚠️ **PENDING** — idem come sopra.

---

## Come Claude Code usa questo file

**A inizio sessione** (dopo lo snapshot Git, vedi Regola 1 di `CLAUDE.md`):
- Non eseguire automaticamente tutti gli smoke test.
- Eseguirli solo se richiesto dall'utente o se il task sta per toccare un componente e serve confermare lo stato di partenza.

**A fine task** (prima di dichiarare "fatto"):
- Individuare quali smoke test sono rilevanti per i componenti toccati.
- Eseguirli uno per uno.
- Riportare l'esito con l'output effettivo. Se anche uno solo fallisce, il task non è completato.

**Aggiunta di nuovi smoke test**:
- Solo su richiesta esplicita dell'utente, oppure quando un task completa una nuova funzionalità verificabile.
- Non inventare smoke test "per sicurezza".
