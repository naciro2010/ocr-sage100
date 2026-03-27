# OCR Sage 100

Micro-service Spring Boot / Kotlin pour le traitement automatisé de factures par OCR et synchronisation avec Sage 100.

## Architecture

```
Facture PDF/Image
    ↓
Apache Tika (extraction texte, JVM natif)
    ↓
Claude API (structuration → JSON)
    ↓
Spring Boot (validation, workflow, API REST)
    ↓
PostgreSQL (stockage et suivi)
    ↓
Connecteur Sage 100 (REST API)
```

**Un seul langage (Kotlin), un seul runtime (JVM), une seule équipe.**

## Stack technique

- **Kotlin 2.1** + **Spring Boot 3.4**
- **Apache Tika** pour l'extraction de texte (PDF, images, scans)
- **Claude API** pour l'extraction structurée des données de facturation
- **PostgreSQL** + **Flyway** pour la persistance
- **Docker Compose** pour le déploiement

## API REST

| Méthode | Endpoint                    | Description                      |
|---------|-----------------------------|----------------------------------|
| POST    | `/api/invoices`             | Upload et traitement d'une facture |
| GET     | `/api/invoices`             | Liste paginée des factures       |
| GET     | `/api/invoices/{id}`        | Détail d'une facture             |
| POST    | `/api/invoices/{id}/sync`   | Synchronisation vers Sage 100    |
| GET     | `/api/invoices/dashboard`   | Statistiques du tableau de bord  |

## Démarrage rapide

### Prérequis

- Java 21+
- Docker & Docker Compose

### Lancer avec Docker Compose

```bash
# Configurer la clé API Claude
export CLAUDE_API_KEY=sk-ant-...

# Lancer PostgreSQL + l'application
docker compose up -d
```

### Développement local

```bash
# Lancer uniquement PostgreSQL
docker compose up -d db

# Lancer l'application
export CLAUDE_API_KEY=sk-ant-...
./gradlew bootRun
```

### Tester l'upload d'une facture

```bash
curl -X POST http://localhost:8080/api/invoices \
  -F "file=@facture.pdf"
```

## Configuration

Variables d'environnement :

| Variable          | Description                    | Défaut                      |
|-------------------|--------------------------------|-----------------------------|
| `CLAUDE_API_KEY`  | Clé API Anthropic              | (requis)                    |
| `SAGE100_BASE_URL`| URL de l'API Sage 100          | `http://localhost:8443`     |
| `SAGE100_API_KEY` | Clé API Sage 100               | (optionnel)                 |

## Workflow de traitement

1. **UPLOADED** → Facture reçue
2. **OCR_IN_PROGRESS** → Extraction de texte par Tika
3. **OCR_COMPLETED** → Texte brut extrait
4. **AI_EXTRACTION_IN_PROGRESS** → Analyse par Claude API
5. **EXTRACTED** → Données structurées extraites
6. **READY_FOR_SAGE** → Validation OK, prête pour sync
7. **SAGE_SYNCED** → Synchronisée avec Sage 100
