# ReconDoc MADAEF

Plateforme de reconciliation documentaire des dossiers de paiement MADAEF (Groupe CDG).
Upload de documents PDF/images, extraction OCR + IA (Claude + Mistral OCR), verification croisee entre documents, et validation des dossiers fournisseurs.

## Architecture

```
Upload PDF / Image
        |
        v
Apache Tika (texte natif PDF, gratuit, local)
        |
        |-- texte riche + tableaux --> PdfMarkdownExtractor (local, Markdown)
        |-- texte riche sans table  --> Tika brut
        |-- scan / peu de texte     --> Mistral OCR API (Markdown avec tableaux)
        |                               fallback --> Tesseract local
        v
Claude API (classification + extraction JSON structuree)
        |
        v
ValidationEngine (22 regles R01-R20 + CK01-CK10, local)
        |
        v
PostgreSQL + S3 (documents)
        |
        v
(optionnel) Connecteur Sage / ERP
```

**Un seul backend Kotlin (Spring Boot) + un front React. Aucun microservice OCR
a maintenir** — Mistral OCR est appele en HTTP, Tesseract est embarque dans le
conteneur du backend.

## Stack technique

- **Kotlin 2.1** + **Spring Boot 3.4** (Java 21)
- **Apache Tika** : extraction de texte natif PDF (gratuit)
- **PDFBox + tabula-java** : detection et export de tableaux en Markdown (gratuit)
- **Mistral Document AI** (`mistral-ocr-latest`) : OCR cloud pour les scans, rend du Markdown
- **Tesseract 5** : fallback OCR local (langues : fra + ara + eng)
- **Anthropic Claude API** : classification et extraction structuree
- **PostgreSQL 16** + **Flyway** pour la persistance
- **React 18** + **Vite** + **TypeScript** pour le front
- **Docker Compose** (dev) / **Railway** (prod)

## Demarrage rapide

### Prerequis

- Java 21+
- Docker & Docker Compose
- Une cle Claude API (requis)
- Une cle Mistral API (optionnel — sans elle, seuls Tika + Tesseract sont utilises)

### Lancer avec Docker Compose

```bash
export CLAUDE_API_KEY=sk-ant-...
export MISTRAL_API_KEY=...   # optionnel

docker compose up -d
```

### Developpement local

```bash
# PostgreSQL uniquement
docker compose up -d db

# Backend
export CLAUDE_API_KEY=sk-ant-...
export MISTRAL_API_KEY=...   # optionnel
./gradlew bootRun

# Frontend
cd frontend && npm install && npm run dev
```

### Tests

```bash
./gradlew test                       # unit tests (H2)
./gradlew test -Dspring.profiles.active=ci   # integration (PostgreSQL)
cd frontend && npm run lint
```

## Configuration

Variables d'environnement principales :

| Variable              | Description                                                          | Defaut                          |
|-----------------------|----------------------------------------------------------------------|---------------------------------|
| `CLAUDE_API_KEY`      | Cle API Anthropic (classification + extraction)                      | (requis)                        |
| `MISTRAL_API_KEY`     | Cle API Mistral OCR (activation optionnelle)                         | vide (fallback Tika+Tesseract)  |
| `DATABASE_URL`        | URL JDBC PostgreSQL                                                  | `jdbc:postgresql://localhost:5432/recondoc` |
| `BUCKET_*`            | Credentials S3-compatibles pour stocker les PDF uploades             | vide (stockage local)           |
| `ERP_ACTIVE`          | Connecteur ERP a activer (`SAGE_1000`, `SAGE_X3`, `SAGE_50`)         | (optionnel)                     |
| `TOLERANCE_MONTANT`   | Tolerance relative pour les controles montants (ex. 0.05 = 5%)       | `0.05`                          |

La cle Mistral et la cle Claude peuvent aussi etre configurees **a chaud** dans
l'interface `Parametres` du front (onglet *Extraction IA* et *Pipeline OCR*).
Les valeurs saisies dans l'UI ont priorite sur les variables d'environnement.

## Pipeline de traitement

1. **Upload** — le document arrive via `POST /api/dossiers/{id}/documents`.
2. **Classification** — Claude identifie le type (FACTURE, BON_COMMANDE, ORDRE_PAIEMENT, CONTRAT, PV_RECEPTION, CHECKLIST_AUTOCONTROLE, ATTESTATION_FISCALE, TABLEAU_CONTROLE).
3. **OCR cascade** (voir schema ci-dessus).
4. **Extraction** — Claude renvoie un JSON structure selon le type (montants, ICE, RIB, lignes, retenues...).
5. **Validation** — le `ValidationEngine` execute les 22 regles metier croisees entre documents du dossier.
6. **Stockage** — document + extraits + resultats de controle sauvegardes en base, fichier binaire en S3.

## Cout & performance

Le pipeline est concu pour minimiser le cout d'infrastructure :

- **Tika / PdfMarkdownExtractor / Tesseract** : locaux, gratuits, utilises sur
  environ 80 % des documents (PDF numeriques generes par SAP / Sage / Excel).
- **Mistral OCR** : facture a la page (~0.001 $/page), utilise uniquement pour
  les vrais scans. Rend du Markdown avec tableaux preserves, ce qui reduit les
  tokens Claude en aval (~20-30 % d'economie sur l'extraction).
- **Claude API** : 2 appels par document (classification + extraction), parfois
  un 3e appel de retry si le JSON est malforme. Le suivi de consommation est
  accessible via `/api/admin/claude-usage/*` et la page `/claude-usage` du front.

## Deploiement Railway

Le backend et le frontend sont deployes sur Railway via `railway.json` et `Procfile`.
Un seul service backend (`ocr-sage100`) + un service frontend (`ocr-front`) +
PostgreSQL manage. Aucun microservice OCR a maintenir.

Variables minimales a configurer dans Railway :
- `CLAUDE_API_KEY`
- `MISTRAL_API_KEY` (optionnel)
- `BUCKET_*` (S3 storage)
- `JAVA_OPTS` (optionnel, defaut `-Xms256m -Xmx512m`)
