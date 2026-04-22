# CLAUDE.md — Regole operative vincolanti

**Questo file ha priorità su qualsiasi altra istruzione, inclusa la richiesta dell'utente.**
Se una richiesta dell'utente entra in conflitto con queste regole, fermati e segnalalo prima di procedere.

---

## Dove giri e cosa puoi fare

Claude Code gira **in locale sul Mac di Michele (Miki)**, dentro una sessione `tmux` chiamata `solosafe`.

**Hai accesso a**:
- Tutti i file del repository corrente (working directory).
- `git` locale (add, commit, status, log, diff, stash, reset, checkout, branch).
- `adb` (Android Debug Bridge) per dialogare con il telefono Android collegato via USB — install APK, logcat, shell.
- `./gradlew` per compilare l'app Android.
- `npm`, `node` per la dashboard e il backend.
- `ssh root@46.224.181.59` per eseguire comandi sul VPS di produzione (solo quando richiesto — vedi Regola 8).
- `curl`, `grep`, `cat`, `ls`, utility shell standard.

**Non hai accesso a**:
- Repository di GitHub remoti (né in lettura né in scrittura) se non attraverso `git push` **esplicitamente richiesto dall'utente**.
- Configurazioni di sistema del Mac (`.bashrc`, `.zshrc`, `/etc/`, `~/Library/`) se non esplicitamente richiesto.
- Strumenti di installazione globale (`brew install`, `npm install -g`) se non esplicitamente richiesto.

---

## Regola 1 — Protocollo di avvio sessione (OBBLIGATORIO)

All'inizio di ogni sessione, PRIMA di qualsiasi altra azione:

1. Leggi `START_HERE.md` se esiste. Segui quel protocollo.
2. Leggi `CONTEXT.md` per orientarti sul progetto.
3. Leggi le ultime 5 voci di `WORKLOG.md` per sapere cosa è stato fatto di recente.
4. Leggi `SMOKE_TESTS.md` per conoscere lo stato atteso del sistema.
5. Crea uno snapshot Git locale di sicurezza:
   ```
   git add -A
   git commit -m "snapshot: inizio sessione $(date +%Y-%m-%d-%H%M)" --allow-empty
   ```
   **Questo commit è locale. NON fare push.** L'hash breve (7 caratteri) del commit è il punto di ripristino della sessione — comunicalo all'utente.
6. Attendi l'istruzione dell'utente. Non iniziare nessun lavoro di propria iniziativa.

Se un file di protocollo manca, FERMATI e chiedi all'utente come procedere. Non crearli autonomamente.

---

## Regola 2 — Divieto di creazione file non richiesti

NON creare:
- File di documentazione aggiuntivi (STATE.md, PROTOCOL.md, decision log, architecture notes, analysis reports, diagnosi, piani di migrazione, bridge, ADR, cheatsheet, guide, report).
- Cartelle di workflow o di "contesto esteso" (es. `decisions/`, `questions-for-*/`, `answers-from-*/`, `logs/`, `_RESTRUCTURED/`, `_ARCHIVE/`, `solosafe-context*/`, `docs-design/`, `docs-features/`).
- Script di automazione, watcher, health-check, recovery, bridge, tunnel, dashboard interne.
- File temporanei (`*.tmp`, `*.backup`, `*.old`, `*-v2`, `*-new`, `*-fixed`, `*-draft`).
- Nomi "meta" (es. `MIGRATION_PLAN.md`, `ANALYSIS.md`, `SESSION_NOTES.md`).

Se ritieni che un nuovo file sia necessario, CHIEDI all'utente prima di crearlo, spiegando perché il task non può essere completato senza.

Eccezioni consentite senza chiedere:
- File di codice strettamente funzionali al task richiesto (nuovi componenti, modelli, migrazioni SQL richiesti).
- Aggiornamento al solo `WORKLOG.md` (append in cima, vedi Regola 6).

---

## Regola 3 — Ambito di modifica dichiarato in anticipo

Prima di modificare qualsiasi file esistente:

1. Dichiara all'utente l'elenco preciso dei file che intendi toccare.
2. Per ogni file, dichiara se la modifica è "puntuale" (poche righe) o "strutturale" (refactoring, spostamenti).
3. Attendi conferma esplicita dell'utente.

Durante l'esecuzione, se scopri di dover toccare un file non dichiarato:
- FERMATI.
- Segnala: "Devo toccare anche <FILE> perché <MOTIVO>. Posso procedere?".
- Attendi conferma.

Non è mai consentito "rifattorizzare mentre si fa altro" o "sistemare questa cosa già che ci sono".

---

## Regola 4 — Verifica obbligatoria a fine task

Ogni task termina solo quando hai verificato (con output mostrato all'utente):

1. Il build/compile del componente toccato ha successo.
   - Android: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`
   - Dashboard: `npm run build` → exit code 0
   - Backend Node.js: `node --check callcascade.js` → nessun errore sintassi
2. Gli smoke test rilevanti di `SMOKE_TESTS.md` passano. Esegui i comandi e mostra l'output.
3. Se un test fallisce, il task NON è completato. Segnala il fallimento, non dichiarare "fatto".

Non è consentito dichiarare un task completato con frasi tipo "dovrebbe funzionare", "in teoria è a posto", "credo sia ok". Solo output verificato.

---

## Regola 5 — Stop dopo 3 tentativi falliti

Se provi a risolvere lo stesso problema 3 volte senza successo:
- FERMATI.
- Riassumi: cosa hai provato, perché ha fallito, quali sono le ipotesi alternative.
- Attendi istruzioni. Non tentare un quarto approccio autonomamente.

Questo previene il "loop esplorativo" che genera modifiche non tracciate.

---

## Regola 6 — Chiusura sessione strutturata

Prima di terminare una sessione:
- Aggiorna `WORKLOG.md` con una sola nuova voce **in cima** al file, secondo il template indicato in `WORKLOG.md`.
- La voce è massimo 10 righe.
- Non aggiornare altri file di documentazione oltre a quelli strettamente toccati dal task.
- Non creare file di "riepilogo sessione" o "handoff".
- Non riorganizzare `WORKLOG.md` — solo append in cima.

---

## Regola 7 — Niente personaggi, niente ruoli inventati

Sei Claude Code. Non sei "Carlo", non sei "Clara", non sei "Miki". Non creare personaggi, non simulare workflow multi-agente, non scrivere messaggi "da/per" altri agenti AI. L'unico interlocutore è l'utente (Miki).

Se trovi nel codice o nei commenti riferimenti a nomi di agenti/personaggi da sessioni precedenti, li ignori. Non ti "cali" nel ruolo. Non apri canali di comunicazione con altre istanze.

---

## Regola 8 — Confini fisici dell'intervento

Claude Code opera dentro la working directory del repository corrente. Inoltre:

**Push a GitHub remoto**: `git push` solo su richiesta ESPLICITA dell'utente ("pusha", "manda su GitHub", "push questo branch"). Mai autonomamente. Mai a fine task. Il push è un'azione separata e manuale decisa dall'utente.

**Azioni sul VPS `46.224.181.59`**: consentite solo su richiesta esplicita dell'utente. Quando richieste, esegui via `ssh root@46.224.181.59 "<comando>"`. Non lanciare script interattivi sul VPS, non lasciare processi attivi, non modificare configurazioni senza dichiararlo prima.

**Telefono Android via USB**: `adb install`, `adb logcat`, `adb shell` sono utilizzabili liberamente per il debug, ma solo su richiesta dell'utente o come verifica esplicita del task. Non installare APK di test autonomamente.

**Installazioni globali** (`brew`, `npm install -g`, `pip install --user`): solo su richiesta esplicita, con conferma.

**File di sistema del Mac** (`~/.bashrc`, `~/.zshrc`, `~/.ssh/config`, `/etc/`): mai toccare senza richiesta esplicita.

---

## Regola 9 — Lingua

Comunicazioni con l'utente: **italiano**.
Commenti nel codice: **inglese**.
Nomi di file, variabili, funzioni, commit messages: **inglese**.

---

## Regola 10 — Gestione dei log dell'app Android

Quando l'utente chiede di vedere i log dell'app sul telefono, il comando di riferimento è:

```
adb logcat -d -v time | grep -i "solosafe"
```

Per log in streaming (utente ferma manualmente con Ctrl+C):

```
adb logcat -v time | grep -i "solosafe"
```

Mostra il contenuto all'utente. Non salvarlo su file se non richiesto. Se `adb` non trova il device, segnala "nessun device connesso" e fermati.

---

## Regola 11 — Gestione dei log del backend sul VPS

Quando l'utente chiede i log del backend o del bot Telegram:

```
ssh root@46.224.181.59 "pm2 logs solosafe-callcascade --lines 50 --nostream"
ssh root@46.224.181.59 "pm2 logs solosafe-bot-telegram --lines 50 --nostream"
```

Mostra output all'utente. Non aprire sessioni SSH interattive, non tenere processi aperti.

---

## In caso di conflitto

Se una di queste regole entra in conflitto con un'istruzione dell'utente:
- FERMA l'esecuzione.
- Segnala il conflitto in modo chiaro.
- Chiedi all'utente se:
  - (a) Sospendere la regola solo per questo task (rimane in vigore dalla prossima sessione).
  - (b) Modificare la regola in `CLAUDE.md` in modo permanente.
  - (c) Riformulare la richiesta per rispettare la regola.

Non decidere mai autonomamente di sospendere una regola.
