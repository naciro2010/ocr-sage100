# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## OBJECTIF #1 : FIABILITE 100%

**La mission du projet est d'avoir des donnees extraites et des verdicts de controles fiables a 100%.** Le cout, la performance et la latence sont secondaires. Toute optimisation doit preserver (ou ameliorer) la justesse des donnees et des verdicts.

Regles appliquees a toute modification de code :
- **Avant** un changement sur l'extraction ou les controles : lancer `./gradlew test --tests "*.GoldenDossiersRegressionTest"`. Tests rouges = bloquant.
- **Apres** un changement : relancer + verifier qu'aucun verdict n'a change sur le jeu golden.
- **Pas de valeur inventee** : si la confidence Claude est < 0.6 ou si le schema est invalide, le champ est `null` + warning explicite.
- **Validation schema/regex** apres chaque extraction IA : ICE 15 chiffres, RIB 24 chiffres, dates ISO, montants positifs.
- **Re-extraction automatique** si `extractionQualityScore < 70` (max 2 essais), puis revue humaine.
- **Toute nouvelle regle** doit venir avec au moins 2 scenarios golden (cas conforme + cas non conforme).
- **Faux negatifs** (laisser passer une erreur) = critique. **Faux positifs** = a reduire mais priorite moindre.

## Project Overview
Plateforme de reconciliation documentaire des dossiers de paiement MADAEF (Groupe CDG). Upload de documents PDF/images, extraction OCR + IA (Claude API), verification croisee entre documents, et validation des dossiers fournisseurs.

## Common Commands

### Backend
```bash
./gradlew build                    # Build + tests
./gradlew test                     # Unit tests (H2 in-memory, profile: test)
./gradlew test -Dspring.profiles.active=ci  # Integration tests (requires PostgreSQL)
./gradlew bootRun                  # Run Spring Boot (requires PostgreSQL + CLAUDE_API_KEY)
./gradlew test --tests "com.madaef.recondoc.ValidationServiceTest"  # Single test class
./gradlew test --tests "*.ValidationServiceTest.testMethodName"     # Single test method
```

### Frontend
```bash
cd frontend && npm install    # Install dependencies
cd frontend && npm run dev    # Dev server (Vite)
cd frontend && npm run build  # Production build
cd frontend && npm run lint   # ESLint check
```

### Docker
```bash
docker compose up -d          # Start all services (db, app, frontend)
docker compose up -d db       # Start only PostgreSQL (for local dev)
```

## Architecture

### Processing Pipeline
```
PDF/Image upload
  -> Apache Tika (texte natif PDF, gratuit)
  -> PdfMarkdownExtractor (Markdown avec tableaux si PDF numerique, local)
  -> Mistral OCR API (si scan, rend du Markdown, optionnel via MISTRAL_API_KEY)
  -> Tesseract local (fallback hors-ligne)
  -> Claude API (classification + extraction JSON structuree)
  -> ValidationEngine (R01-R20 + CK01-CK10)
  -> PostgreSQL
```

### Backend (Kotlin + Spring Boot)
- **Package**: `com.madaef.recondoc` under `src/main/kotlin/`
- **Two domain models coexist**:
  - **Invoice** (`entity/Invoice.kt`): Legacy single-invoice OCR pipeline with status workflow (UPLOADED → OCR_IN_PROGRESS → EXTRACTED → SAGE_SYNCED)
  - **DossierPaiement** (`entity/dossier/`): Multi-document payment dossier system (the primary domain). A dossier groups related documents (facture, bon de commande, contrat, ordre de paiement, PV reception, attestation fiscale, checklist, tableau de controle)
- **Services**:
  - `DossierService`: Orchestrates document upload, classification, extraction, and validation for dossiers
  - `InvoiceService`: Legacy single-invoice processing
  - `service/extraction/`: LLM-based document classification (`ClassificationService`) and data extraction (`LlmExtractionService`) with prompts in `ExtractionPrompts`
  - `service/validation/ValidationEngine`: Cross-document validation rules (montant matching, reference coherence between facture/BC/OP)
  - `OcrService` + `MistralOcrClient`: text extraction cascade — Apache Tika (natif PDF) -> `PdfMarkdownExtractor` (tableaux locaux) -> Mistral OCR API (scans, rend du Markdown) -> Tesseract local (fallback hors-ligne)
  - `ErpConnectorFactory` → `Sage1000Service`, `SageX3Service`, `Sage50Service`: ERP sync (factory pattern, selected via `erp.active` config)
- **Config**: `application.yml` (main), `application-test.yml` (H2, Flyway disabled), `application-ci.yml` (PostgreSQL integration tests)

### Frontend (React + TypeScript)
- SPA with React Router. Pages: Dashboard, DossierList, DossierDetail, Settings
- API client in `src/api/client.ts`, types in `src/api/dossierTypes.ts`
- Vite build, served via Nginx in production

### Database
- PostgreSQL 16 with Flyway migrations in `src/main/resources/db/migration/` (V1 → V13, linear history)
- Tests use H2 in PostgreSQL compatibility mode (profile: `test`) or real PostgreSQL (profile: `ci`)
- JPA with `ddl-auto: validate` in production (schema managed by Flyway)

## Domain Context
- Moroccan invoices: TVA rates (0%, 7%, 10%, 14%, 20%)
- Fiscal identifiers: ICE (15 digits), IF, RC, Patente, CNSS
- RIB bancaire (24 digits)
- Document types: FACTURE, BON_COMMANDE, CONTRAT_AVENANT, ORDRE_PAIEMENT, CHECKLIST_AUTOCONTROLE, TABLEAU_CONTROLE, PV_RECEPTION, ATTESTATION_FISCALE
- Dossier statuses: BROUILLON → EN_VERIFICATION → VALIDE / REJETE
- Montant tolerance for cross-document validation: configurable via `app.tolerance-montant` (default 5%)

## Key Environment Variables
- `CLAUDE_API_KEY`: Required for AI extraction (classification + extraction structuree)
- `MISTRAL_API_KEY`: Optional — active Mistral OCR comme moteur OCR principal pour les scans. Sans cette cle, la cascade utilise Tika + Tesseract (local, sans cout externe)
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`: PostgreSQL connection
- `ERP_ACTIVE`: Which ERP connector to use (SAGE_1000, SAGE_X3, SAGE_50)
- `BUCKET_*`: S3-compatible object storage for uploaded files

## RGPD — sous-traitants IA (a maintenir a jour)

**Consigne permanente** : tout PR qui touche la chaine externe (OCR, LLM, API tierce) doit mettre a jour ce bloc **et** la memoire `project_rgpd.md`. Si un nouveau provider, un nouveau champ envoye, un changement de region/retention, une activation DPA/ZDR ou une demande d'effacement intervient, la mise a jour est obligatoire dans le meme PR.

### Donnees personnelles envoyees a l'exterieur

| Destinataire | Donnees envoyees | Region | Retention par defaut |
|---|---|---|---|
| Mistral OCR (`api.mistral.ai`) | Image/PDF complet des scans | France / UE | ~30 j (ZDR possible B2B) |
| Anthropic Claude (`api.anthropic.com`) | Texte OCR **pseudonymise** + prompts + few-shots synthetiques | USA (AWS, DPF UE-US) | 30 j par defaut, ZDR via contrat entreprise |

Nature des PII originales : emails, telephones MA, RIB 24 chiffres, noms precedes de civilite, ICE/IF/RC (B2B), raison sociale fournisseurs, signatures manuscrites sur PV.

### Protections en place
- **Pseudonymisation automatique avant Claude** (`PseudonymizationService`, actif par defaut via `ai.pseudonymization.enabled=true`) : emails, telephones MA, RIB 24 chiffres, noms avec civilite remplaces par tokens opaques `[EMAIL_N]` / `[PHONE_N]` / `[RIB_N]` / `[PERSON_N]`. ICE / IF / RC / montants / raisons sociales non masques (identifiants B2B publics / necessaires aux regles R09-R14). Mapping in-memory uniquement, jamais persiste. Detokenisation appliquee AVANT grounding + stockage : les donnees reelles ne quittent jamais le SI Maroc.
- Cache OCR SHA-256 (evite re-envois Mistral)
- Logs applicatifs sans contenu OCR
- Prompt caching : seulement few-shots synthetiques, jamais de donnees clients
- GroundingValidator refuse les valeurs absentes du texte OCR (pas d'hallucination stockee)

### Checklist DPO MADAEF (etat a tenir a jour ici)
- [x] Pseudonymisation automatique active cote applicatif (`PseudonymizationService`)
- [ ] DPA signe avec Anthropic (+ adhesion Data Privacy Framework)
- [ ] DPA signe avec Mistral
- [ ] Zero Data Retention Anthropic active
- [ ] Inscription au registre des traitements (art. 30 RGPD)
- [ ] Clauses Contractuelles Types pour transfert US (si DPF insuffisant)
- [ ] Information des signataires PV / politique fournisseur a jour
- [ ] Procedure droit a l'effacement documentee (S3 + Postgres + propagation sous-traitants)

Details et historique : memoire `project_rgpd.md` + consigne `feedback_rgpd_maintenance.md`.

## Vision Produit & Regles Metier

### Objectif principal
Rapprochement et controle de coherence entre les documents d'un dossier de paiement : factures, bons de commande (ou contrats), et ordres de paiement. Tout doit etre coherent, tous les documents listes, corrects et coherents entre eux.

### Pipeline
1. **Extraction exhaustive** : OCR + IA pour extraire TOUTES les donnees de chaque document
2. **Rapprochement** via 3 couches de controles, explicitement distinguees dans l'UI (tag "Systeme" / "IA" / "Humain") :
   - **Couche 1 — Systeme (R01-R20)** : regles deterministes codees en Kotlin, parametrables, < 100 ms, 0 $
   - **Couche 2 — IA (CUSTOM-XX)** : regles personnalisees ecrites en francais dans Settings > Regles. `CustomRuleService.evaluateBatch()` envoie TOUTES les regles applicables a un dossier dans un **seul appel Claude** (partage du contexte ⇒ cout et latence divises par N). Fallback par regle si le batch renvoie du JSON invalide ou depasse le timeout.
   - **Couche 3 — Humain (CK01-CK10)** : checklist autocontrole CCF-EN-04 saisie par un operateur (signatures, habilitations, PV reception). Le systeme lit le statut mais ne le recalcule pas.

### Exigences UX controles (NON NEGOCIABLES)
1. **Visibilite** : voir exactement ce qui a ete controle pour chaque regle
2. **Acces document** : acces direct au document source / extrait depuis le controle
3. **Correction** : corriger les donnees extraites OU le controle lui-meme (surtout autocontrole)
4. **Re-lancement granulaire** : relancer un seul controle individuellement
5. **Cascade** : si une donnee change et touche plusieurs controles, les relancer TOUS

### Regles de validation (22 regles)
- R01-R03: Concordance montants facture vs BC (TTC, HT, TVA, taux)
- R04-R05: Montant OP vs facture (avec/sans retenues)
- R06: Verification arithmetique retenues (base x taux = montant)
- R07-R08: References facture/BC citees dans OP
- R09-R11: Coherence ICE, IF, RIB entre documents
- R12: Checklist completude (10 points mappes aux documents source)
- R13: Tableau controle financier completude
- R14: Coherence nom fournisseur entre documents
- R15: Grille tarifaire x duree = HT facture (CONTRACTUEL uniquement)
- R16: Verification arithmetique HT + TVA = TTC
- R17a-R17b: Coherence temporelle (BC/Contrat → Facture → OP)
- R18: Validite attestation fiscale (fenetre 6 mois)
- R20: Completude dossier (documents requis presents)
- R21: Anti-doublon facture (meme numero OU meme fournisseur+montant+date +/-3j sur 12 mois glissants)
- R22: Paiement posterieur a la reception (date OP >= date PV_RECEPTION ; CONTRACTUEL uniquement)

## CI/CD
- GitHub Actions (`.github/workflows/`): builds backend (Gradle) and frontend (npm), runs unit tests on H2 then integration tests on PostgreSQL
- Deployment: Railway (config in `railway.json`, `Procfile`)

## Git Workflow (OBLIGATOIRE)
- **Jamais de push direct sur `main`.** Pour toute modification, workflow impose :
  1. `git checkout -b feat/<slug>` ou `fix/<slug>` depuis main a jour
  2. Commits sur la branche
  3. `git push -u origin <branch>`
  4. `gh pr create` avec titre + description
  5. **Attendre la CI verte** via `gh pr checks --watch` ou `gh run list`
  6. Merger uniquement quand tous les checks sont verts : `gh pr merge --squash --delete-branch`
- Si on est deja sur une feature branch, rester dessus (ne pas en recreer une).
- Les commits directs sur main sont reserves aux cas d'urgence explicitement autorises par le mainteneur.

## Sub-agents spécialisés (persistés dans `.claude/agents/`)

Quatre sub-agents dédiés, chacun responsable d'un domaine unique. Ils s'améliorent en continu pour : **meilleur résultat, moindre coût, meilleure performance**. À invoquer avec l'outil `Agent` en passant `subagent_type: "<name>"`.

| Sub-agent | Mission | Quand l'invoquer |
|-----------|---------|------------------|
| `extraction-optimizer` | Optimiser chaîne OCR (Tika/Mistral/Tesseract) + Claude (classification + extraction). Coût / latence / précision. | "Réduire coût Claude", "OCR lent", "prompt caching", "split modèles classif/extract", "cache Mistral" |
| `extraction-auditor` | Vérifier que l'extraction est complète (champs obligatoires, confidence validée, cohérence arithmétique). Score qualité composite + re-extraction ciblée. | "Champs manquants", "score qualité", "détecter extractions dégradées", "re-extraction auto" |
| `controls-optimizer` | Optimiser le moteur de règles (R01-R20 + CUSTOM batch). Mémoïsation, pré-calcul features, profiling par règle, prompt caching batch. | "Règles lentes", "CUSTOM-XX cher", "instrumenter par règle", "memoïsation montants" |
| `controls-auditor` | Auditer la justesse des contrôles (faux positifs/négatifs, couverture métier, drift). Proposer règles manquantes, jeu de dossiers golden. | "Auditer justesse contrôles", "trop de faux positifs", "règle manquante", "couverture métier" |

### Règles d'intervention des sub-agents
- **Frontières strictes**: chaque agent a un périmètre de fichiers explicité dans son fichier `.md`. Il ne sort pas de son scope.
- **Un changement ROI par PR**: pas de refonte, pas d'optimisation cumulée. Une PR = un gain mesurable.
- **Respect du git workflow**: feature branch + PR + CI verte. Jamais de commit direct sur `main`.
- **Pas de régression**: les tests existants doivent continuer à passer. Ajout de tests obligatoire pour tout nouveau comportement.
- **Mesure avant/après**: tout changement de perf/coût doit être mesuré et documenté dans la description de PR.
- **Pas de commentaires AI**, texte humain, logs en français, conventions Kotlin/TS idiomatiques.

### Plan d'amélioration continue (vision)

**Sprint 1 — impact rapide (cible: -20% coût Claude)**
- `extraction-optimizer`: split modèles (Haiku classif + Sonnet extract), `max_tokens` dynamique, header `anthropic-version` à jour, retry avec jitter.
- `controls-optimizer`: pré-calcul `DossierFeatures` partagé, instrumentation `duration_ms` par règle.

**Sprint 2 — qualité données**
- `extraction-auditor`: `ExtractionQualityService` + `MandatoryFields` contract + score composite + re-extraction auto si <60.
- `controls-auditor`: audit top 5 règles les plus NOK, jeu golden dossiers (10-15 cas).

**Sprint 3 — scale & observabilité**
- `extraction-optimizer`: cache OCR cross-dossier (SHA-256), prompt caching Anthropic, métriques Prometheus par engine.
- `controls-optimizer`: prompt caching batch CUSTOM, chunking si >25 règles, graphe de dépendances runtime.
- `controls-auditor`: règles proposées R21-R25 (anti-doublon, paiement post-réception, TVA/catégorie, complétude lignes, séparation pouvoirs).

### KPIs globaux à surveiller
- **Coût moyen par dossier validé** (€, tendance hebdo)
- **Latence p95** upload → extraction terminée
- **Taux d'extraction complète au 1er passage** (cible >85%)
- **Taux de faux positifs par règle** (cible <20%)
- **Disponibilité API Claude/Mistral** (taux d'erreur < 2%)
