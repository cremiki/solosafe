# START_HERE.md — Leggi questo per primo

**Questo file esiste per una sola ragione: dare a Claude Code un punto di partenza non ambiguo a inizio sessione.**

---

## Sei Claude Code e stai aprendo questo repository? Segui questi passi in ordine, senza saltarne nessuno.

### Passo 1 — Leggi i file di protocollo, in quest'ordine

1. `CLAUDE.md` — **regole operative vincolanti**. Leggile tutte. Sono il contratto con l'utente.
2. `CONTEXT.md` — cos'è SoloSafe, i componenti, stack, schema DB, convenzioni, stato del Mac atteso.
3. Le ultime 5 voci di `WORKLOG.md` — cosa è stato fatto nelle sessioni recenti.
4. `SMOKE_TESTS.md` — cosa deve sempre funzionare (ma non eseguirli ora — solo consultazione).
5. `DEPLOY.md` se esiste — come si passa dal locale alla produzione.

### Passo 2 — Snapshot Git locale di inizio sessione

Dalla root del repository:

```
git add -A
git commit -m "snapshot: inizio sessione $(date +%Y-%m-%d-%H%M)" --allow-empty
```

Prendi nota dell'hash corto (prime 7 cifre). **NON fare push.** Il push avviene solo su richiesta esplicita dell'utente.

### Passo 3 — Presentati all'utente con questo messaggio

Rispondi con esattamente queste informazioni (sostituendo i placeholder):

```
Sessione inizializzata.
- Repo corrente: <nome-repo>
- Protocollo letto: CLAUDE.md, CONTEXT.md, SMOKE_TESTS.md, WORKLOG.md
- Ultime voci del WORKLOG viste: <numero>
- Snapshot di inizio sessione: <hash 7 caratteri>
- Ambiente: locale sul Mac. tmux session: solosafe (se applicabile).
- Telefono USB: <connesso: sì/no — verifica con `adb devices` solo se richiesto>

In attesa di istruzioni.
```

Poi **fermati** e attendi il task. Non proporre cose da fare, non suggerire refactoring, non esplorare il codice "per farti un'idea". Aspetta.

---

## Cosa fare se qualcosa non torna

- Se uno dei file di protocollo manca → chiedi all'utente prima di procedere.
- Se `git add && commit` fallisce → chiedi all'utente prima di continuare.
- Se il repository ha modifiche non committate non tue → chiedi se vanno conservate (`git stash`) o scartate, prima dello snapshot.
- Se `tmux` non è installato o la sessione `solosafe` non esiste → non creare tu la sessione, segnalalo all'utente.

---

## Cosa NON fare

- Non creare file (vedi Regola 2 di `CLAUDE.md`).
- Non creare cartelle.
- Non riorganizzare la documentazione.
- Non "ottimizzare" nulla di tua iniziativa.
- Non assumere personaggi o ruoli. Sei Claude Code.
- Non parlare con altre istanze AI. Non esistono per te.
- Non fare `git push` (vedi Regola 8 di `CLAUDE.md`).
- Non eseguire comandi sul VPS se non espressamente richiesto.
- Non installare nulla globalmente senza richiesta esplicita.

---

## Alla fine della sessione

Prima di chiudere, aggiorna `WORKLOG.md` con una nuova voce **in cima** (sotto l'intestazione, sopra le voci esistenti), rispettando il template scritto in `WORKLOG.md`. Una sola voce, massimo 10 righe.

Poi comunica all'utente:
- Cosa hai fatto (una frase).
- Quali smoke test hai eseguito e l'esito.
- L'hash del commit di snapshot iniziale (per eventuale ripristino).
- Se il task è completato o parziale.
- Promemoria se manca un push su GitHub che l'utente potrebbe voler fare.

Fine sessione.
