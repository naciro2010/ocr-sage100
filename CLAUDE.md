# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
PDF/Image upload → Apache Tika (text extraction) → Claude API (structured JSON extraction) → ValidationEngine (cross-document checks) → PostgreSQL
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
  - `OcrService` + `PaddleOcrClient`: Text extraction from PDFs/images via Tika, Tesseract, or PaddleOCR
  - `ErpConnectorFactory` → `Sage1000Service`, `SageX3Service`, `Sage50Service`: ERP sync (factory pattern, selected via `erp.active` config)
- **Config**: `application.yml` (main), `application-test.yml` (H2, Flyway disabled), `application-ci.yml` (PostgreSQL integration tests)

### Frontend (React + TypeScript)
- SPA with React Router. Pages: Dashboard, DossierList, DossierDetail, Settings
- API client in `src/api/client.ts`, types in `src/api/dossierTypes.ts`
- Vite build, served via Nginx in production

### Database
- PostgreSQL 16 with Flyway migrations in `src/main/resources/db/migration/`
- **Two conflicting V5 migrations exist** (V5__app_settings.sql and V5__dossier_paiement_madaef.sql) - Flyway will fail on fresh DB
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
- `CLAUDE_API_KEY`: Required for AI extraction
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`: PostgreSQL connection
- `ERP_ACTIVE`: Which ERP connector to use (SAGE_1000, SAGE_X3, SAGE_50)
- `BUCKET_*`: S3-compatible object storage for uploaded files

## CI/CD
- GitHub Actions (`.github/workflows/`): builds backend (Gradle) and frontend (npm), runs unit tests on H2 then integration tests on PostgreSQL
- Deployment: Railway (config in `railway.json`, `Procfile`)
