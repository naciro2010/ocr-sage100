# Guide Complet d'Integration ERP Sage - OCR Sage 100

## Table des Matieres

1. [Vue d'ensemble de l'architecture](#1-vue-densemble-de-larchitecture)
2. [Integration Sage 1000 (Objets Metiers)](#2-integration-sage-1000-objets-metiers)
3. [Integration Sage X3 (Enterprise Management)](#3-integration-sage-x3-enterprise-management)
4. [Integration Sage 50 (Ciel Compta)](#4-integration-sage-50-ciel-compta)
5. [Configuration Multi-ERP](#5-configuration-multi-erp)
6. [Mapping des Champs Marocains](#6-mapping-des-champs-marocains)
7. [Formats d'Export](#7-formats-dexport)
8. [Securite et Authentification](#8-securite-et-authentification)
9. [Deploiement et Mise en Production](#9-deploiement-et-mise-en-production)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Vue d'ensemble de l'architecture

### Architecture globale

```
+------------------+     +-------------------+     +------------------+
|                  |     |                   |     |                  |
|  Frontend React  +---->+  Backend Spring   +---->+  ERP Connector   |
|  (Upload, View)  |     |  Boot (API REST)  |     |  Factory         |
|                  |     |                   |     |                  |
+------------------+     +--------+----------+     +---+----+----+---+
                                  |                    |    |    |
                         +--------v----------+         |    |    |
                         |   PostgreSQL 16   |    +----v-+  |  +-v------+
                         |   (Stockage)      |    |Sage  |  |  |Sage   |
                         +-------------------+    |1000  |  |  |50     |
                                                  +------+  |  +-------+
                                                         +--v---+
                                                         |Sage  |
                                                         |X3    |
                                                         +------+
```

### Pipeline de traitement

```
Upload PDF/Image
       |
       v
  Apache Tika (OCR)
       |
       v
  Extraction Regex (deterministe)
       |
       v
  Tabula (tables PDF)
       |
       v
  Claude AI (enrichissement si necessaire)
       |
       v
  Validation Marocaine
       |
       v
  Synchronisation ERP (Sage 1000 / X3 / 50)
       |
       v
  Export (CSV / JSON / UBL / EDI)
```

### Connecteurs ERP disponibles

| ERP | Protocole | Authentification | Cas d'usage |
|-----|-----------|-----------------|-------------|
| **Sage 1000** | REST (Objets Metiers) | Bearer Token | Grandes entreprises marocaines |
| **Sage X3** | REST/OData (Syracuse) | OAuth2 Client Credentials | Groupes multi-sites |
| **Sage 50** | REST Bridge (SDK) | Basic Auth | PME/TPE marocaines |

---

## 2. Integration Sage 1000 (Objets Metiers)

### 2.1 Presentation

Sage 1000 expose ses objets metiers via une couche REST .NET. L'API "Objets Metiers" permet de creer, lire, modifier et supprimer des ecritures comptables directement dans Sage 1000.

### 2.2 Pre-requis

- Sage 1000 v9.0+ installe avec le module Achats
- Service Web Objets Metiers active (IIS/.NET)
- Compte API avec droits sur les ecritures d'achat
- Certificat SSL si HTTPS

### 2.3 Configuration

```yaml
# application.yml
sage1000:
  base-url: ${SAGE1000_BASE_URL:http://sage-server:8443}
  api-key: ${SAGE1000_API_KEY:votre-cle-api}
  timeout: 30s
  company-code: ${SAGE1000_COMPANY_CODE:MAROC01}
```

Variables d'environnement Docker :
```bash
SAGE1000_BASE_URL=https://sage1000.votreentreprise.ma:8443
SAGE1000_API_KEY=sk-sage1000-xxxxxxxxxxxx
SAGE1000_COMPANY_CODE=MAROC01
```

### 2.4 Endpoint principal

```
POST /api/objets-metiers/ecritures-achat
Content-Type: application/json
Authorization: Bearer {api-key}
X-Company-Code: {company-code}
```

### 2.5 Payload de synchronisation

```json
{
  "TypeDocument": "FA",
  "Reference": "FAC-2024-001234",
  "DatePiece": "2024-03-15",
  "DateEcheance": "2024-04-15",
  "Fournisseur": {
    "RaisonSociale": "MAROC DISTRIBUTION SARL",
    "ICE": "001234567890123",
    "IF": "12345678",
    "RC": "RC-CASA-123456",
    "Patente": "PAT-12345678",
    "Adresse": "123 Bd Mohammed V",
    "Ville": "Casablanca"
  },
  "MontantHT": 10000.00,
  "TauxTVA": 20.00,
  "MontantTVA": 2000.00,
  "MontantTTC": 12000.00,
  "Remise": 0.00,
  "Devise": "MAD",
  "ModeReglement": "Virement",
  "Banque": "Attijariwafa Bank",
  "RIB": "007780001234567890123456",
  "Lignes": [
    {
      "Description": "Fournitures de bureau",
      "Quantite": 100,
      "PrixUnitaireHT": 50.00,
      "TauxTVA": 20.00,
      "MontantHT": 5000.00,
      "MontantTVA": 1000.00,
      "MontantTTC": 6000.00
    },
    {
      "Description": "Papier A4 ramette",
      "Quantite": 200,
      "PrixUnitaireHT": 25.00,
      "TauxTVA": 20.00,
      "MontantHT": 5000.00,
      "MontantTVA": 1000.00,
      "MontantTTC": 6000.00
    }
  ]
}
```

### 2.6 Reponse

```json
{
  "NumeroEcriture": "ECR-2024-005678",
  "Status": "OK",
  "DateCreation": "2024-03-15T14:30:00"
}
```

### 2.7 Codes d'erreur courants

| Code | Signification | Solution |
|------|--------------|----------|
| 401 | Token invalide | Regenerer la cle API dans Sage |
| 403 | Droits insuffisants | Verifier les permissions du compte |
| 409 | Doublon detecte | La facture existe deja (meme reference) |
| 422 | Donnees invalides | Verifier le format des champs |
| 503 | Service indisponible | Verifier que le service Objets Metiers est demarre |

---

## 3. Integration Sage X3 (Enterprise Management)

### 3.1 Presentation

Sage X3 (anciennement Sage Enterprise Management) utilise le serveur web Syracuse pour exposer des APIs REST/OData. C'est l'ERP le plus puissant de la gamme Sage, adapte aux grands groupes multi-sites.

### 3.2 Pre-requis

- Sage X3 v12+ (Syracuse 12+)
- Module PURCHASING active
- Application OAuth2 configuree dans Syracuse
- Acces reseau au serveur Syracuse (port 8124 par defaut)

### 3.3 Configuration

```yaml
# application.yml
sagex3:
  base-url: ${SAGEX3_BASE_URL:https://syracuse.example.com:8124}
  client-id: ${SAGEX3_CLIENT_ID:}
  client-secret: ${SAGEX3_CLIENT_SECRET:}
  folder: ${SAGEX3_FOLDER:MAROC}
  pool-alias: ${SAGEX3_POOL_ALIAS:x3}
  token-url: ${SAGEX3_TOKEN_URL:https://syracuse.example.com:8124/auth/oauth/token}
```

### 3.4 Authentification OAuth2

```
POST /auth/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id={client-id}
&client_secret={client-secret}
&scope=api
```

Reponse :
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "api"
}
```

### 3.5 Creation d'une facture d'achat

```
POST /api/x3/erp/{folder}/SPURCHASE
Content-Type: application/json
Authorization: Bearer {access_token}
X-Sage-FolderId: {folder}
X-Sage-PoolAlias: {pool-alias}
```

### 3.6 Payload X3

```json
{
  "BPSINV0_1": {
    "SIVTYP": "INV",
    "BPSNUM": "FOUR-001",
    "BPSINV": "FAC-2024-001234",
    "INVDAT": "2024-03-15",
    "INVDUDDAT": "2024-04-15",
    "CUR": "MAD",
    "BPTNUM": "FOUR-001"
  },
  "BPSINV0_2": {
    "BPSNAM": "MAROC DISTRIBUTION SARL",
    "BPSADDLIG": "123 Bd Mohammed V",
    "CTY": "Casablanca",
    "CRY": "MA",
    "CRN": "001234567890123"
  },
  "BPSINV1_1": [
    {
      "ITMREF": "ART-001",
      "ITMDES": "Fournitures de bureau",
      "QTY": 100,
      "NETPRI": 50.00,
      "VAT": "MAR20",
      "AMTNOTLIN": 5000.00
    }
  ],
  "BPSINV2_1": {
    "AMTNOT": 10000.00,
    "AMTTAX": 2000.00,
    "AMTATI": 12000.00,
    "PTE": "VIREMENT"
  }
}
```

### 3.7 Mapping des champs X3

| Champ OCR | Champ X3 | Table X3 | Description |
|-----------|----------|----------|-------------|
| supplierName | BPSNAM | BPSINV0_2 | Raison sociale fournisseur |
| supplierIce | CRN | BPSINV0_2 | ICE (Company Registration Number) |
| invoiceNumber | BPSINV | BPSINV0_1 | Numero de facture |
| invoiceDate | INVDAT | BPSINV0_1 | Date piece |
| paymentDueDate | INVDUDDAT | BPSINV0_1 | Date echeance |
| amountHt | AMTNOT | BPSINV2_1 | Montant hors taxe |
| amountTva | AMTTAX | BPSINV2_1 | Montant TVA |
| amountTtc | AMTATI | BPSINV2_1 | Montant TTC |
| currency | CUR | BPSINV0_1 | Devise (MAD) |
| paymentMethod | PTE | BPSINV2_1 | Mode reglement |
| lineItems[].description | ITMDES | BPSINV1_1 | Description ligne |
| lineItems[].quantity | QTY | BPSINV1_1 | Quantite |
| lineItems[].unitPriceHt | NETPRI | BPSINV1_1 | Prix unitaire HT |
| lineItems[].tvaRate | VAT | BPSINV1_1 | Code TVA (MAR0/7/10/14/20) |

### 3.8 Codes TVA marocains dans X3

| Taux | Code X3 | Description |
|------|---------|-------------|
| 0% | MAR0 | Exonere |
| 7% | MAR7 | Taux reduit (produits de base) |
| 10% | MAR10 | Taux intermediaire |
| 14% | MAR14 | Taux intermediaire superieur |
| 20% | MAR20 | Taux normal |

---

## 4. Integration Sage 50 (Ciel Compta)

### 4.1 Presentation

Sage 50 (anciennement Ciel Compta) est le logiciel comptable le plus repandu au Maroc pour les PME/TPE. L'integration se fait via un pont REST qui communique avec le SDK Sage 50.

### 4.2 Pre-requis

- Sage 50 Comptabilite v2023+ installe
- Sage 50 REST Bridge installe sur le poste serveur
- Fichier d'entreprise (.SAI) accessible
- Port 9090 ouvert (bridge REST)

### 4.3 Configuration

```yaml
# application.yml
sage50:
  base-url: ${SAGE50_BASE_URL:http://localhost:9090}
  username: ${SAGE50_USERNAME:admin}
  password: ${SAGE50_PASSWORD:}
  company-file: ${SAGE50_COMPANY_FILE:MAROC_2024.SAI}
  journal-code: ${SAGE50_JOURNAL_CODE:ACH}
  fiscal-year: ${SAGE50_FISCAL_YEAR:2024}
```

### 4.4 Endpoint

```
POST /api/v1/purchase-invoices
Content-Type: application/json
Authorization: Basic {base64(username:password)}
```

### 4.5 Payload Sage 50

```json
{
  "journalCode": "ACH",
  "fiscalYear": 2024,
  "invoiceNumber": "FAC-2024-001234",
  "invoiceDate": "2024-03-15",
  "dueDate": "2024-04-15",
  "supplier": {
    "name": "MAROC DISTRIBUTION SARL",
    "account": "401000",
    "taxId": "001234567890123",
    "address": "123 Bd Mohammed V, Casablanca"
  },
  "lines": [
    {
      "accountCode": "607100",
      "label": "Fournitures de bureau",
      "debit": 5000.00,
      "credit": 0.00,
      "vatCode": "TVA20",
      "vatAmount": 1000.00
    },
    {
      "accountCode": "445660",
      "label": "TVA deductible 20%",
      "debit": 2000.00,
      "credit": 0.00
    },
    {
      "accountCode": "401000",
      "label": "MAROC DISTRIBUTION SARL",
      "debit": 0.00,
      "credit": 12000.00
    }
  ],
  "paymentMethod": "VIREMENT",
  "bankAccount": "512100",
  "reference": "FAC-2024-001234",
  "currency": "MAD"
}
```

### 4.6 Plan comptable marocain (PCGE)

| Compte | Libelle | Usage |
|--------|---------|-------|
| 401000 | Fournisseurs | Credit fournisseur |
| 445660 | TVA deductible | Debit TVA recuperable |
| 607100 | Achats de marchandises | Debit achat |
| 611000 | Achats de fournitures | Debit fournitures |
| 512100 | Banque | Reglement |
| 530000 | Caisse | Reglement especes |

### 4.7 Codes TVA Sage 50 Maroc

| Code | Taux | Compte |
|------|------|--------|
| TVA0 | 0% | 445660 |
| TVA7 | 7% | 445660 |
| TVA10 | 10% | 445660 |
| TVA14 | 14% | 445660 |
| TVA20 | 20% | 445660 |

---

## 5. Configuration Multi-ERP

### 5.1 Selection de l'ERP

L'application supporte la configuration dynamique de l'ERP cible via l'interface Settings ou les variables d'environnement.

```yaml
# application.yml
erp:
  active: ${ERP_ACTIVE:SAGE_1000}  # SAGE_1000, SAGE_X3, SAGE_50
```

### 5.2 Architecture du Factory Pattern

```kotlin
// ErpConnectorFactory selectionne automatiquement le bon connecteur
interface ErpConnector {
    fun syncInvoice(invoice: Invoice): SageSyncResult
    fun testConnection(): Boolean
}

enum class ErpType { SAGE_1000, SAGE_X3, SAGE_50 }
```

### 5.3 Basculer entre les ERP

**Via l'interface web :**
1. Aller dans Settings > Configuration ERP
2. Selectionner l'ERP cible
3. Renseigner les parametres de connexion
4. Tester la connexion
5. Sauvegarder

**Via les variables d'environnement :**
```bash
# Sage 1000
ERP_ACTIVE=SAGE_1000
SAGE1000_BASE_URL=https://sage1000.example.ma:8443
SAGE1000_API_KEY=sk-xxxxx

# Sage X3
ERP_ACTIVE=SAGE_X3
SAGEX3_BASE_URL=https://syracuse.example.ma:8124
SAGEX3_CLIENT_ID=ocr-sage-app
SAGEX3_CLIENT_SECRET=secret-xxxxx
SAGEX3_FOLDER=MAROC

# Sage 50
ERP_ACTIVE=SAGE_50
SAGE50_BASE_URL=http://sage50-bridge:9090
SAGE50_USERNAME=admin
SAGE50_PASSWORD=password
SAGE50_COMPANY_FILE=MAROC_2024.SAI
```

---

## 6. Mapping des Champs Marocains

### 6.1 Identifiants Fiscaux

| Champ | Format | Validation | Obligatoire |
|-------|--------|-----------|-------------|
| **ICE** | 15 chiffres exactement | Regex `^\d{15}$` | Oui (depuis 2018) |
| **IF** (Identifiant Fiscal) | 7-8 chiffres | Regex `^\d{7,8}$` | Oui |
| **RC** (Registre Commerce) | Variable, prefixe ville | Alphanumetique | Oui |
| **Patente** | 7-8 chiffres | Regex `^\d{7,8}$` | Oui |
| **CNSS** | 8-10 chiffres | Regex `^\d{8,10}$` | Oui |

### 6.2 RIB Bancaire Marocain

Format : **24 chiffres** = Code banque (3) + Code ville (3) + N compte (16) + Cle RIB (2)

| Banque | Code | Prefixe RIB |
|--------|------|-------------|
| Attijariwafa Bank | 007 | 007xxx... |
| BMCE Bank (Bank of Africa) | 011 | 011xxx... |
| Banque Populaire | 101 | 101xxx... |
| BMCI (BNP Paribas) | 013 | 013xxx... |
| Societe Generale Maroc | 022 | 022xxx... |
| CIH Bank | 021 | 021xxx... |
| Credit du Maroc | 045 | 045xxx... |
| Credit Agricole du Maroc | 060 | 060xxx... |
| CDG Capital | 190 | 190xxx... |
| Al Barid Bank | 350 | 350xxx... |

### 6.3 TVA Marocaine

| Taux | Application | Compte comptable |
|------|------------|-----------------|
| **0%** | Exportations, zones franches | 445660 |
| **7%** | Eau, electricite, produits pharmaceutiques | 445660 |
| **10%** | Huiles alimentaires, sel, riz, pates | 445660 |
| **14%** | Transport, electricite (usage domestique) | 445660 |
| **20%** | Taux normal (majorite des biens et services) | 445660 |

### 6.4 Modes de Paiement

| Mode | Code Sage 1000 | Code Sage X3 | Code Sage 50 |
|------|---------------|-------------|-------------|
| Virement | VIR | VIREMENT | VIR |
| Cheque | CHQ | CHEQUE | CHQ |
| Especes | ESP | ESPECES | ESP |
| Traite | TRT | TRAITE | TRT |
| Effet | EFF | EFFET | EFF |
| LCN | LCN | LCN | LCN |
| Carte bancaire | CB | CARTE | CB |
| Prelevement | PRV | PRELEVEMENT | PRV |
| Compensation | CMP | COMPENSATION | CMP |

---

## 7. Formats d'Export

### 7.1 Export CSV

Format CSV compatible Excel avec :
- Separateur : point-virgule (;) pour compatibilite locale
- Encodage : UTF-8 avec BOM
- En-tetes en francais

```csv
N Facture;Date;Fournisseur;ICE;Montant HT;TVA;Montant TTC;Devise;Statut
FAC-001;15/03/2024;MAROC DIST;001234567890123;10000,00;2000,00;12000,00;MAD;SAGE_SYNCED
```

### 7.2 Export JSON

```json
{
  "exportDate": "2024-03-15T14:30:00",
  "invoices": [
    {
      "id": 1,
      "invoiceNumber": "FAC-001",
      "supplierName": "MAROC DISTRIBUTION",
      "supplierIce": "001234567890123",
      "amountHt": 10000.00,
      "amountTva": 2000.00,
      "amountTtc": 12000.00,
      "lineItems": [...]
    }
  ]
}
```

### 7.3 Export UBL 2.1 (Universal Business Language)

Standard international de facturation electronique. Compatible avec :
- FacturX (France/Allemagne)
- Peppol (Europe)
- Reglementations e-invoicing marocaines a venir

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
  <ID>FAC-2024-001234</ID>
  <IssueDate>2024-03-15</IssueDate>
  <InvoiceTypeCode>380</InvoiceTypeCode>
  <DocumentCurrencyCode>MAD</DocumentCurrencyCode>
  <AccountingSupplierParty>
    <Party>
      <PartyName><Name>MAROC DISTRIBUTION SARL</Name></PartyName>
      <PartyTaxScheme>
        <CompanyID>001234567890123</CompanyID>
        <TaxScheme><ID>VAT</ID></TaxScheme>
      </PartyTaxScheme>
    </Party>
  </AccountingSupplierParty>
  <LegalMonetaryTotal>
    <TaxExclusiveAmount currencyID="MAD">10000.00</TaxExclusiveAmount>
    <TaxInclusiveAmount currencyID="MAD">12000.00</TaxInclusiveAmount>
  </LegalMonetaryTotal>
</Invoice>
```

### 7.4 Export EDI (EDIFACT INVOIC D96A)

Format d'echange EDI pour les grandes entreprises :

```
UNH+1+INVOIC:D:96A:UN'
BGM+380+FAC-2024-001234+9'
DTM+137:20240315:102'
NAD+SE+++MAROC DISTRIBUTION SARL+123 Bd Mohammed V+Casablanca++MA'
RFF+VA:001234567890123'
MOA+86:10000.00:MAD'
MOA+176:2000.00:MAD'
MOA+77:12000.00:MAD'
UNT+12+1'
```

---

## 8. Securite et Authentification

### 8.1 Bonnes pratiques

1. **Ne jamais stocker les cles API en clair** dans le code source
2. Utiliser les **variables d'environnement** ou un coffre-fort (Vault)
3. **Chiffrer les communications** (HTTPS/TLS obligatoire en production)
4. **Rotation des cles** tous les 90 jours
5. **Logs d'audit** pour chaque synchronisation

### 8.2 Configuration Docker securisee

```yaml
# docker-compose.yml (production)
services:
  app:
    environment:
      - SAGE1000_API_KEY=${SAGE1000_API_KEY}  # depuis .env
      - SAGEX3_CLIENT_SECRET=${SAGEX3_CLIENT_SECRET}
      - SAGE50_PASSWORD=${SAGE50_PASSWORD}
    secrets:
      - sage_api_key
      - sage_x3_secret

secrets:
  sage_api_key:
    file: ./secrets/sage1000_api_key.txt
  sage_x3_secret:
    file: ./secrets/sagex3_client_secret.txt
```

### 8.3 Reseau

```
Internet ──> [Nginx/Reverse Proxy] ──> [App Backend :8080]
                                              |
                                    [Reseau prive Docker]
                                              |
                          +-------------------+-------------------+
                          |                   |                   |
                     [PostgreSQL]        [Sage Server]      [Sage Bridge]
                       :5432             :8443 / :8124         :9090
```

---

## 9. Deploiement et Mise en Production

### 9.1 Checklist de deploiement

- [ ] Base de donnees PostgreSQL provisionnee
- [ ] Variables d'environnement configurees (API keys, URLs)
- [ ] Certificats SSL installes
- [ ] Test de connexion ERP reussi
- [ ] Migrations Flyway executees
- [ ] Health check fonctionnel (`/actuator/health`)
- [ ] Logs configures (niveau INFO en production)
- [ ] Sauvegardes automatiques de la base

### 9.2 Docker Compose Production

```bash
# Lancer
docker compose -f docker-compose.yml up -d

# Verifier
docker compose logs -f app

# Tester
curl http://localhost:8080/actuator/health
```

### 9.3 Railway / Heroku

```bash
# Railway
railway login
railway init
railway up

# Variables d'environnement
railway variables set SAGE1000_BASE_URL=https://...
railway variables set SAGE1000_API_KEY=sk-...
railway variables set CLAUDE_API_KEY=sk-ant-...
```

---

## 10. Troubleshooting

### Problemes courants

| Probleme | Cause probable | Solution |
|----------|---------------|----------|
| Connexion refusee a Sage | Firewall / port bloque | Verifier les regles reseau |
| Token expire (Sage X3) | Token OAuth2 expire | Le service renouvelle automatiquement |
| Doublon de facture | Meme reference deja envoyee | Verifier le numero de facture unique |
| OCR illisible | PDF scan de mauvaise qualite | Rescanner a 300 DPI minimum |
| TVA mal extraite | Format non reconnu | Verifier les patterns regex |
| ICE invalide | Moins de 15 chiffres | Corriger manuellement puis resync |
| Timeout Sage | Serveur lent ou surcharge | Augmenter sage1000.timeout |
| Erreur 422 Sage | Champs obligatoires manquants | Verifier validation avant sync |

### Logs utiles

```bash
# Backend logs
docker compose logs -f app | grep -i "sage"
docker compose logs -f app | grep -i "error"

# PostgreSQL
docker compose exec db psql -U ocrsage -c "SELECT id, status, error_message FROM invoices WHERE status = 'SAGE_SYNC_FAILED'"
```

### API de diagnostic

```bash
# Verifier la sante de l'application
curl http://localhost:8080/actuator/health

# Dashboard stats
curl http://localhost:8080/api/invoices/dashboard

# Valider une facture
curl http://localhost:8080/api/invoices/1/validate
```
