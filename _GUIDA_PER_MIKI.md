# Guida operativa per Miki

Questo documento spiega cosa fare con i file di protocollo e come aprire correttamente la nuova sessione di Claude Code.

---

## I 6 file di protocollo

| File | A cosa serve | Chi lo scrive |
|---|---|---|
| `START_HERE.md` | Punto di ingresso ad ogni nuova sessione di Claude Code. Lo legge per primo. | Tu all'inizio, non cambia quasi mai |
| `CLAUDE.md` | 11 regole operative vincolanti (cosa può e non può fare Claude Code). | Tu e io, con modifiche esplicite |
| `CONTEXT.md` | Fotografia del progetto (componenti, stack, DB, convenzioni). | Tu e io, si aggiorna quando evolve |
| `SMOKE_TESTS.md` | Lista delle cose che devono funzionare sempre. | Si arricchisce man mano |
| `WORKLOG.md` | Diario di bordo append-only, una voce per sessione. | Claude Code a fine sessione |
| `DEPLOY.md` | Procedure di deploy dal Mac al VPS/telefono. | Tu e io, raramente cambia |

Vanno messi nella **root** dei tuoi 3 repo:
- `solosafe` (app Android)
- `solosafe-dashboard` (dashboard React)
- `solosafe-backend` (quando lo creerai per il backend Node.js)

---

## I primi 30 minuti della nuova sessione

### Preparazione (tu, senza Claude Code)

**1. Chiudi tutto quello che è aperto di Claude Code**: la sessione corrente va azzerata (tmux, chat, terminali). Fai tabula rasa.

**2. Verifica dove vuoi lavorare**: di solito `~/Projects/` sul Mac. Se hai già una cartella di progetti vecchia, decidi se usarla o crearne una nuova.

**3. Installa o aggiorna tmux** (se non lo hai già): apri Terminale sul Mac, digita:
```
brew install tmux
```
Se hai già Homebrew. Se non hai Homebrew, dillo a Claude Code nella prima sessione — ti aiuterà a installarlo.

**4. Crea la sessione tmux persistente**:
```
tmux new-session -s solosafe
```
Questa è la sessione che non morirà mai finché non la chiudi tu esplicitamente. Se chiudi il terminale per sbaglio, riapri e fai `tmux attach -t solosafe` per tornare dove eri.

Per "staccarti" dalla sessione senza chiuderla: `Ctrl+B` poi `D`. Per riattaccarti: `tmux attach -t solosafe`.

### Prima sessione Claude Code: "Sessione 0 — Inventario e clonazione repo"

Questa sessione ha un obiettivo solo: **preparare l'ambiente**. Non toccheremo codice del prodotto.

**1. Dentro tmux, lancia Claude Code**:
```
claude
```

**2. Copia i 6 file di protocollo in una cartella temporanea sul Mac**: ad esempio `~/Desktop/solosafe-guardrails/`. Li trovi scaricabili alla fine di questa conversazione.

**3. Primo prompt in Claude Code** (copia-incolla testuale):

> Sei in una nuova sessione. Abbiamo deciso un nuovo metodo di lavoro. Prima di fare qualsiasi cosa leggi le istruzioni operative che ho preparato.
>
> Per favore:
> 1. Verifica cosa è installato sul Mac: git, node, npm, java, adb, tmux. Per ciascuno dimmi la versione o dimmi che manca.
> 2. Se Git non è configurato (user.name, user.email) dimmelo, lo configureremo.
> 3. Non installare nulla. Solo verifica.
>
> Fatto questo, aspetto il prossimo step.

Claude Code ti risponderà con un elenco di cosa ha trovato. Tu leggi, mi riporti qui l'esito e decidiamo insieme cosa installare se manca qualcosa.

**4. Secondo prompt** (dopo che abbiamo sistemato eventuali mancanze):

> Ora cloniamo i repo GitHub. Crea la cartella `~/Projects/solosafe-workspace/` se non esiste. Entra dentro. Clona:
> - `https://github.com/cremiki/solosafe.git`
> - `https://github.com/cremiki/solosafe-dashboard.git`
>
> Dopo il clone, per ciascun repo esegui `git log --oneline | head -10` e riportami i risultati. Non toccare nient'altro.

**5. Terzo prompt** (installi i file di protocollo):

> Ora installiamo i file di protocollo in ciascun repo. Copia questi 6 file dalla cartella `~/Desktop/solosafe-guardrails/` nella root di ciascuno dei 2 repo (solosafe e solosafe-dashboard):
> - CLAUDE.md
> - CONTEXT.md
> - SMOKE_TESTS.md
> - WORKLOG.md
> - START_HERE.md
> - DEPLOY.md
>
> Per ciascun repo fai `git add . && git commit -m "chore: add Claude Code guardrails"`. Non fare push. Dimmi quando hai finito.

**6. Quarto prompt**:

> Perfetto. Ora siamo pronti per lavorare con il nuovo metodo. Chiudi questa sessione Claude Code qui (senza chiudere tmux). Apro una nuova sessione da dentro un repo specifico per cominciare.

### A questo punto

Da qui in avanti, ogni nuova sessione Claude Code:
1. Tu fai `cd ~/Projects/solosafe-workspace/solosafe` (o l'altra dashboard, a seconda di cosa vuoi fare)
2. `claude`
3. Primo prompt: **"Leggi `START_HERE.md` e segui il protocollo."**
4. Claude Code si presenta con il formato previsto, aspetta istruzioni
5. Tu gli dai il task, preparato insieme a me su una chat di claude.ai dedicata

---

## Come interagiamo io e te nelle prossime settimane

Ti suggerisco di organizzarti così con le chat su claude.ai (o Claude app):

- **"SoloSafe — Strategia & Prodotto"** → dove discutiamo direzione, priorità, decisioni architetturali
- **"SoloSafe — Task per Code"** → dove mi chiedi di spacchettare qualcosa in task passabili a Claude Code (ne apri una nuova per ogni batch di task correlati, così non si saturano)
- **"SoloSafe — Analisi e debug"** → per fare retrospettive, capire cosa non funziona, analizzare log
- **"SoloSafe — Documentazione"** → per aggiornare CONTEXT, SMOKE_TESTS, ADR, Project Brief

In ciascuna chat, il primo messaggio che mi mandi puoi sempre strutturarlo così:

> Sto lavorando al progetto SoloSafe. Puoi leggere `Progetti/solosafe/` su Drive per il contesto. Per questa chat mi serve aiuto su [strategia / task spacchettamento / debug / documentazione]. Ecco il punto di partenza: [...]

Io leggo Drive, so già dove siamo, rispondo in modo coerente.

---

## Regole per te (sì, anche per te)

**1. Non chiedere a Claude Code di "sistemare un po' in giro"**. Se gli lasci spazio interpretativo, torna il casino. Task sempre piccoli e chiusi, preferibilmente preparati con me prima.

**2. Non aggirare le regole di `CLAUDE.md` "giusto questa volta"**. Se una regola ti sta stretta, cambiamola in `CLAUDE.md` in modo esplicito. Il metodo funziona solo se è costante.

**3. Se Claude Code viola una regola, fermalo subito**. "Hai violato la Regola X. Torna allo snapshot di inizio sessione." Non lasciar correre.

**4. Un push su GitHub è una decisione tua, non di Claude Code**. Lui ti prepara il commit, tu guardi, tu dici "pusha".

**5. Alla fine della giornata, controlla `WORKLOG.md`**. Due minuti. Ti permette di sapere dove sei senza affidarti solo alla memoria di Claude Code.

---

## Scenari frequenti e come gestirli

### "Voglio vedere i log dell'app sul telefono"
Prompt a Claude Code: "Collega il telefono via USB. Verifica con `adb devices`. Poi mostrami gli ultimi 50 log con tag SoloSafe."

### "Voglio vedere i log del backend in produzione"
Prompt a Claude Code: "Mostrami gli ultimi 50 log di `solosafe-callcascade` dal VPS."

### "Ho fatto una modifica ma ora l'app non compila"
Prompt a Claude Code: "Torna allo snapshot di inizio sessione (hash `<hash>`) con `git reset --hard`. Verifica che il build funzioni. Poi ragioniamo insieme su come riprovare."

### "Voglio mandare la versione che abbiamo sul VPS"
Prompt a Claude Code: "Leggi `DEPLOY.md`, sezione `Deploy dashboard in produzione`. Eseguilo passo per passo. Conferma a me prima di fare rsync."

### "Claude Code ha iniziato a fare troppe cose insieme"
Prompt: "Fermati. Stai violando la Regola 3 di CLAUDE.md. Torna allo snapshot e ripartiamo."

---

## Cosa fare quando cambia qualcosa del progetto

- **Aggiungi un componente nuovo** (es. app Ricevitore): aggiorna `CONTEXT.md` nella sezione "Componenti".
- **Decidi un'ADR**: aggiorni il Decision Log v7 su Drive e menzioni la decisione in `CONTEXT.md`.
- **Una funzionalità diventa verificabile**: aggiungi lo smoke test in `SMOKE_TESTS.md`.
- **Vuoi cambiare una regola**: modifichi `CLAUDE.md` e committi con `docs: update CLAUDE.md rule X`.

Tutte queste modifiche sono manuali e consapevoli, non automatiche.

---

## Una cosa importante

**Tutto quello che Claude Code non ricorda, lo ricordano i file del repo**. Se una sessione finisce per qualsiasi motivo (limite di token, Mac che va in sleep, riavvio, tu che chiudi per distrazione), la nuova sessione apre, legge i file, e **riprende da dove eri**. Non perdi nulla — a patto che Claude Code abbia aggiornato `WORKLOG.md` alla fine.

Se Claude Code si dimentica di aggiornare il `WORKLOG.md`, è un problema: la prossima sessione non saprà cosa è successo. È una delle violazioni su cui non bisogna lasciar correre (Regola 6). Se te ne accorgi quando hai già chiuso, puoi riaprire e dirgli: "Aggiungi al WORKLOG.md la voce della sessione precedente, ricostruendola dal `git log` dei commit delle ultime ore."
