# DEPLOY.md — Come passare dal locale alla produzione

**Principio**: il deploy non è mai automatico. Si fa **solo su richiesta esplicita dell'utente** ("deploya il backend", "pubblica la dashboard", "aggiorna il VPS", ecc.). Fino a quel momento, ogni modifica resta sul Mac di Miki.

**Prima di deployare qualsiasi cosa**, sempre:
1. Fare un commit sul Mac con messaggio descrittivo.
2. Eseguire gli smoke test locali rilevanti per il componente.
3. Se passano, procedere col deploy. Altrimenti fermarsi.

---

## Deploy dashboard in produzione (VPS `46.224.181.59`)

**Quando**: l'utente chiede "deploya la dashboard" o equivalente.

**Passi**:

1. Sul Mac, dalla root del repo dashboard:
   ```
   npm run build
   ```
   Verificare exit code 0 e che la cartella `build/` sia stata generata.

2. Copiare i file sul VPS:
   ```
   rsync -avz --delete build/ root@46.224.181.59:/opt/solosafe/solosafe-dashboard/build/
   ```

3. Nginx serve la cartella `build/` direttamente (già configurato in `/etc/nginx/sites-enabled/solosafe-dashboard`), quindi non serve restart. Se per qualche motivo servisse:
   ```
   ssh root@46.224.181.59 "nginx -t && systemctl reload nginx"
   ```

4. Smoke test post-deploy:
   ```
   curl -s http://46.224.181.59:5000 | grep -o '<title>[^<]*</title>'
   ```
   Output atteso: tag `<title>` non vuoto.

5. Comunicare all'utente esito.

---

## Deploy backend alarm service (quando il repo `cremiki/solosafe-backend` esisterà)

**Quando**: l'utente chiede "deploya il backend" o equivalente.

**Passi** (da finalizzare una volta che il backend sarà su Git):

1. Sul Mac, dalla root del repo backend, committare le modifiche.

2. Push del branch corrente su GitHub (richiede conferma esplicita dell'utente per Regola 8).

3. Sul VPS:
   ```
   ssh root@46.224.181.59 "cd /opt/solosafe/alarm-service && git pull && pm2 restart solosafe-callcascade"
   ```

4. Verificare stato:
   ```
   ssh root@46.224.181.59 "pm2 status solosafe-callcascade"
   ssh root@46.224.181.59 "curl -s http://localhost:3001/health"
   ```

5. Smoke test: SMOKE-BE-01 e SMOKE-BE-02.

6. Comunicare esito.

**Finché il backend vive solo sul VPS (non su Git)**: non fare deploy, sei in modalità "il codice in produzione è la copia autoritativa". Se serve modificarlo, farlo sul VPS direttamente con `vi`/`nano`, ma questo è fragile — la priorità è portare il backend su Git prima possibile.

---

## Deploy APK sul telefono (per test)

**Quando**: l'utente chiede "installa l'APK sul telefono" o equivalente, con telefono collegato USB.

**Passi**:

1. Sul Mac, dalla root del repo Android:
   ```
   ./gradlew assembleDebug
   ```

2. Verificare che l'APK sia stato generato:
   ```
   ls -la app/build/outputs/apk/debug/app-debug.apk
   ```

3. Verificare che il telefono sia visibile:
   ```
   adb devices
   ```
   Output atteso: almeno una riga con `<device_id>	device` (non `unauthorized`, non `offline`).

4. Installare:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Output atteso: `Success`.

5. Per lanciare l'app dal terminale:
   ```
   adb shell am start -n com.solosafe.app/.MainActivity
   ```

6. Per vedere i log dopo l'avvio:
   ```
   adb logcat -d -v time | grep -i "solosafe" | tail -50
   ```

---

## Deploy APK "ufficiale" sul server per distribuzione ai clienti

**Quando**: l'utente chiede di "pubblicare l'APK" o "aggiornare l'APK distribuito".

**Passi** (flusso legacy basato su `watcher.sh`, probabilmente da semplificare in futuro):

1. Sul Mac, `./gradlew assembleRelease` (versione firmata per produzione — richiede keystore configurato).

2. Copia sul VPS:
   ```
   scp app/build/outputs/apk/release/app-release.apk root@46.224.181.59:/opt/solosafe/apk-releases/solosafe-latest.apk
   ```

3. Aggiornare `build-info.json` sul VPS:
   ```
   ssh root@46.224.181.59 "echo '{\"version\":\"'$(date +%Y%m%d-%H%M)'\",\"built_at\":\"'$(date -u +%Y-%m-%dT%H:%M:%SZ)'\",\"downloads\":0}' > /opt/solosafe/apk-releases/build-info.json"
   ```

4. L'APK è accessibile via `https://46.224.181.59:8443/apk/solosafe-latest.apk`.

---

## Rollback di un deploy andato male

**Dashboard**: se il deploy rompe la dashboard in produzione, tornare alla versione precedente.
```
ssh root@46.224.181.59 "cd /opt/solosafe/solosafe-dashboard && git log --oneline | head -5"
# scegliere il commit buono, poi:
ssh root@46.224.181.59 "cd /opt/solosafe/solosafe-dashboard && git checkout <commit-buono> && cd build && ..."
```

In alternativa, sul Mac, `git checkout <commit-buono>`, `npm run build`, rsync verso il VPS.

**Backend**: `pm2 restart solosafe-callcascade` dopo aver fatto `git checkout <commit-buono>` nella cartella del backend sul VPS.

**APK**: ripristinare l'APK precedente dal backup in `/opt/solosafe/apk-releases/archive/`.

---

## Cosa serve prima di deployare ogni volta (checklist)

Claude Code esegue mentalmente questa lista prima di ogni deploy:

- [ ] Ho committato tutto in locale? (`git status` deve essere clean o avere solo file irrilevanti)
- [ ] Gli smoke test locali rilevanti passano?
- [ ] Ho chiesto conferma esplicita all'utente?
- [ ] Se deploy dashboard: ho fatto `npm run build` con successo?
- [ ] Se deploy backend: ho comunicato all'utente che farò restart del servizio (pochi secondi di downtime)?
- [ ] Ho pronto il rollback? (sapere qual è il commit precedente stabile)
