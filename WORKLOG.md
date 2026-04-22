# WORKLOG.md — Diario di bordo

**Regole di scrittura (vincolanti per Claude Code)**:
- Le voci si aggiungono **in cima** al file (la più recente è la prima sotto questa intestazione).
- Una voce per sessione, massimo 10 righe.
- **Mai** modificare voci esistenti. **Mai** riorganizzare l'ordine.
- Rispettare il template sotto, esattamente.

**Template di una voce**:
```
## YYYY-MM-DD HHMM — <titolo breve della sessione>

- **Obiettivo**: <una frase>
- **File toccati**: <elenco file e cartelle, non "vari">
- **Verificato con**: <ID smoke test o comando eseguito, con output sintetico>
- **Esito**: <completato | parziale | bloccato>
- **Snapshot pre-sessione**: <hash corto 7 caratteri del commit di inizio>
- **Git push eseguito?**: <sì, su branch X | no, solo commit locali>
- **Note**: <solo se necessarie, max 2 righe>
```

---

## 2026-04-22 2335 — Primo build e install app Android: 3 smoke test verdi

- **Obiettivo**: verificare che l'app Android compili dalla riga di comando e funzioni sul telefono collegato.
- **File toccati**: app/src/main/kotlin/com/solosafe/app/data/remote/SupabaseClient.kt (1 riga), app/src/main/kotlin/com/solosafe/app/service/SmsAlertManager.kt (1 riga), WORKLOG.md (aggiornamento).
- **Verificato con**: SMOKE-APP-01 (build passato), SMOKE-APP-02 (install passato), SMOKE-APP-04 (log attivi con operator_id).
- **Esito**: completato.
- **Snapshot pre-sessione**: 96af4ed.
- **Git push eseguito?**: no, solo commit locale (gestione push da Terminale dopo accettazione modifiche).
- **Note**: risolti 2 errori Kotlin preesistenti che impedivano build dalla CLI (NETWORK_TYPE_NONE non esistente, add senza JsonPrimitive). JAVA_HOME e ANDROID_HOME impostati solo per sessione.

---

## 2026-04-22 — Setup guardrails iniziali

- **Obiettivo**: introdurre i 6 file di protocollo (`CLAUDE.md`, `CONTEXT.md`, `SMOKE_TESTS.md`, `WORKLOG.md`, `START_HERE.md`, `DEPLOY.md`) in ogni repo SoloSafe, per vincolare le sessioni future a un metodo disciplinato.
- **File toccati**: aggiunti i 6 file nella root del repo.
- **Verificato con**: —
- **Esito**: completato (setup infrastrutturale, non modifiche al codice del prodotto).
- **Snapshot pre-sessione**: —
- **Git push eseguito?**: no, solo commit locale.
- **Note**: prima voce del log. Da qui in avanti ogni sessione di Claude Code aggiunge una voce.
