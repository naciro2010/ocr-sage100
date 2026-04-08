# CLAUDE.md - Project Instructions for ReconDoc MADAEF

## Project Overview
Plateforme de reconciliation documentaire des dossiers de paiement MADAEF (Groupe CDG). Extraction IA, verification croisee entre documents, et validation des dossiers fournisseurs.

## Tech Stack

### Backend (Kotlin + Spring Boot)
- **Language**: Kotlin 2.1 / Java 21
- **Framework**: Spring Boot 3.4.4 (JPA, Validation, Actuator, WebFlux)
- **Build**: Gradle (Kotlin DSL) - `./gradlew`
- **Database**: PostgreSQL 16 + Flyway migrations
- **OCR/PDF**: Apache Tika 3.0, Tabula 1.0.5, PDFBox
- **AI**: Claude API (Anthropic) - modèle `claude-sonnet-4-6`
- **Tests**: JUnit 5, H2 in-memory

### Frontend (React + TypeScript)
- **Language**: TypeScript 5.9
- **Framework**: React 19 + React Router DOM 7
- **Build**: Vite 8
- **Icons**: Lucide React
- **Linting**: ESLint 9 + typescript-eslint
- **Serving**: Nginx (production)

### Infrastructure
- **Containers**: Docker + Docker Compose
- **Database**: PostgreSQL 16 Alpine
- **Deployment**: Railway / Heroku
- **CI/CD**: GitHub Actions

## Common Commands

### Backend
```bash
./gradlew build          # Build backend
./gradlew test           # Run tests
./gradlew bootRun        # Run Spring Boot app
./gradlew bootJar        # Create executable JAR
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
docker compose up -d          # Start all services
docker compose down           # Stop all services
docker compose build          # Rebuild images
docker compose logs -f app    # Follow backend logs
```

## Project Structure
- `backend/` - Kotlin Spring Boot API
- `frontend/` - React TypeScript SPA
- `docker-compose.yml` - Orchestration (db, app, frontend)

## Domain Context
- Factures marocaines (TVA: 0%, 7%, 10%, 14%, 20%)
- Identifiants fiscaux: ICE (15 chiffres), IF, RC, Patente, CNSS
- RIB bancaire (24 chiffres)
- Intégration Sage 1000 via REST API (Objets Métiers)
- Modes de paiement: Virement, Chèque, Espèces, Traite, Effet, LCN, Carte, Prélèvement, Compensation

## Code Conventions
- Backend: Kotlin idiomatique, data classes pour DTOs
- Frontend: Composants fonctionnels React, TypeScript strict
- API: REST JSON, uploads multipart
- DB migrations: Flyway (SQL files)
- Configs: application.yml (Spring), .env (Docker)
