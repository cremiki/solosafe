# CONTEXT.md — Cos'è SoloSafe

**Aggiornato**: 2026-04-22
**Proprietario prodotto**: Michele ("Miki") — cremiki@gmail.com
**Fonte di verità autoritativa del prodotto**: i 4 `.docx` v7 in `Progetti/solosafe/Documentazione di Analisi/Piano/` su Google Drive (Project Brief v7, Decision Log v7, Flussi Operativi v7, Piano Master v2).

---

## Cos'è il prodotto

SoloSafe è una **piattaforma SaaS multi-tenant** per la sicurezza dei lavoratori isolati (lone workers). Pricing: **€7/slot concorrente/mese** (piano base) + add-on modulari. Target: PMI italiane.

Copre obblighi del D.Lgs. 81/2008 art. 45. GDPR con dati in UE.

---

## Componenti e dove vivono

| Componente | Tecnologia | Repo Git | Path locale Mac (convenzione) | Path produzione |
|---|---|---|---|---|
| App Android operatore | Kotlin + Jetpack Compose | `cremiki/solosafe` | `~/Projects/solosafe/` | APK installato sui device |
| Dashboard web | React 19 + TypeScript + Supabase JS | `cremiki/solosafe-dashboard` | `~/Projects/solosafe-dashboard/` | VPS `46.224.181.59`, port `5000` dietro Nginx |
| Backend alarm service | Node.js + Express | (da creare: `cremiki/solosafe-backend`) | (da clonare quando creato) | `/opt/solosafe/alarm-service/` sul VPS, port `3001`, PM2 `solosafe-callcascade` |
| Bot Telegram | Node.js | (insieme al backend, quando creato) | (da clonare) | `/opt/solosafe/` sul VPS, PM2 `solosafe-bot-telegram` |
| Database | Supabase PostgreSQL + Realtime | n/a | n/a | Supabase cloud (per ora — self-hosted Hetzner in Fase 2) |

**Non iniziare senza istruzione esplicita**:
- App Ricevitore React Native (Android+iOS) — pianificata, non iniziata
- Integrazione MDM Headwind — pianificata
- Integrazione BLE SafeX (tag BlueUp) — pianificata
- Integrazione WhatsApp Business API (add-on A5) — pianificata
- Stripe billing — pianificata per Fase 2

---

## Ambiente di sviluppo: il Mac di Miki

**Ipotesi di partenza**: Miki ha un Mac (macOS). Alcuni strumenti potrebbero essere già installati, altri no. Il primo compito di Claude Code alla prima sessione dedicata è **verificare cosa è disponibile**. Comandi di verifica:

```
which git && git --version
which node && node --version
which npm && npm --version
which java && java -version
which adb && adb version
which ./gradlew  # solo se si è dentro un repo Android
```

Componenti attesi per lavorare bene:
- Git ≥ 2.30
- Node.js ≥ 18 (preferibilmente 20)
- npm ≥ 9
- JDK 17 (per compilare l'app Android con Gradle 8+)
- Android SDK con `adb` (di solito installato tramite Android Studio o `brew install android-platform-tools`)
- `tmux` (per sessioni persistenti — vedi `START_HERE.md`)

Se qualche componente manca, Claude Code lo segnala all'utente e chiede se installarlo.

---

## Flusso funzionale essenziale

1. L'operatore apre l'app Android e sceglie tipo di sessione (CONTINUA / TURNO / INTERVENTO / SPAZIO_CONFINATO) e durata → stato `PROTETTO`.
2. Durante la sessione, i sensori (accelerometro, giroscopio) rilevano cadute, immobilità, cambio postura. Se è collegato il tag SafeX BLE, doppio sensore ridondante.
3. Se scatta un evento → **pre-allarme 30 secondi** con countdown + pulsante "STO BENE". Se l'operatore non annulla, parte l'allarme.
4. Cascata multi-canale:
   - **Livello 1** (on-device, offline-capable): SMS GSM nativo (`SmsManager`) + chiamate GSM a cascata ai contatti.
   - **Livello 2** (server-side, se L1 fallisce o non copre): backend `callcascade.js` → SMS via Messagenet, chiamate via Twilio (con DTMF opzionale), notifiche Telegram.
5. Tutti gli eventi loggati su Supabase (`alarm_events`, `alarm_event_log`).
6. Dashboard mostra stato live via Supabase Realtime (WebSocket).

---

## Schema database (7 tabelle principali Supabase)

- `operators` — anagrafica + tunables (cascade_max_rounds, cascade_timeout_seconds, cascade_delay_seconds, default_preset, login_pin, duress_pin, emergency_contacts JSON)
- `emergency_contacts` — contatti di emergenza (name, phone, sms_enabled, call_enabled, telegram_chat_id, dtmf_required)
- `work_sessions` — sessioni di protezione (operator_id, session_type, preset_used, planned_end, actual_end, status)
- `operator_status` — stato live (state: standby/protected/offline, last_lat, last_lng, battery_phone, battery_tag, last_seen)
- `alarm_events` — allarmi (operator_id, type: FALL/IMMOBILITY/SOS/SESSION_EXPIRED, lat, lng, is_duress, confirmation_level)
- `alarm_event_log` — log esecuzione allarme (alarm_event_id, channel: gsm/sms/twilio/telegram, recipient, status, response_by)
- `app_config_log` — audit trail modifiche configurazione

Tutte le tabelle figlie di `operators` devono avere `ON DELETE CASCADE`. **Verifica non ancora eseguita** — vedi `SMOKE_TESTS.md` SMOKE-DB-02.

---

## Convenzioni di codice

- **Lingue nei file**: Kotlin (Android), TypeScript (dashboard), JavaScript (backend).
- **Commenti e nomi**: inglese.
- **Comunicazione con l'utente**: italiano.
- **Commit messages**: inglese, Conventional Commits semplificato (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`).
- **Branch**: Claude Code lavora su `main` con commit di snapshot. Branch separati solo se l'utente lo richiede.
- **Push su GitHub**: solo su richiesta esplicita dell'utente (vedi Regola 8 di `CLAUDE.md`).

---

## Criticità note (dal Decision Log v7 — C1-C14)

Decisioni già prese, non discutere se non richiesto:
- **C1** (chiamate Android): GSM nativo primario + Twilio fallback. Lista nera OEM dinamica su `app_config`.
- **C2** (SafeX BLE): specifiche Safety payload documentate. UUID `A565CC841E0E4B5AB2D317C98FF54110`. Byte 27=battery, byte 28=flags, byte 29=RSSI.
- **C3** (Single VPS): accettato per MVP, snapshot + dump DB. Failover in Fase 2.
- **C4** (sessioni): 4 tipi (CONTINUA, TURNO, INTERVENTO, SPAZIO_CONFINATO) con scadenza T-15, T-0, T+5, T+8.
- **C5** (soglie fall): 6 preset (OFFICE 2.0g, WAREHOUSE 2.8g, CONSTRUCTION 3.5g, INDUSTRY 4.0g, VEHICLE disabled, ALTITUDE 2.0g).
- **C11** (countdown): minimo 3 secondi, obbligatorio.

Non modificare queste decisioni senza istruzione esplicita dell'utente.

---

## Deploy in produzione (sintesi)

Il deploy non è mai automatico. Si fa solo su richiesta esplicita dell'utente ("deploya il backend", "pubblica la dashboard", ecc.). I passaggi dettagliati sono in `DEPLOY.md`.

---

## Note di contesto importanti

- Miki non è uno sviluppatore. Parlagli in italiano chiaro, senza gergo tecnico eccessivo. Quando proponi un'azione, spiega cosa farà e perché.
- Miki rivende già soluzioni loneworker hardware (Twig, Teltonika) ai suoi clienti. SoloSafe è il prodotto software che colma funzioni mancanti dell'hardware esistente.
- Il budget tecnico è contenuto. Non suggerire scelte che richiedono infrastruttura costosa o servizi premium senza motivazione forte.
- Il progetto era a "GREEN LIGHT — Ready for Device Testing" il 2026-04-20. Una serie di modifiche destabilizzanti in sessioni Claude Code precedenti (nomi fittizi di agenti, riorganizzazioni documentali, bridge Python su porta 8899, workflow multi-agente inventati) hanno compromesso lo stato. Il setup attuale (questi 5 file di protocollo) esiste per evitare che accada di nuovo.
