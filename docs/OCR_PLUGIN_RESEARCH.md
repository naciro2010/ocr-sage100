# Recherche OCR Plugin - Meilleures Solutions pour OCR Sage 100

> Date: 2026-04-03
> Contexte: Application de traitement OCR de factures marocaines (arabe + francais)
> Stack actuel: Kotlin/Spring Boot + Apache Tika 3.0 + Tabula 1.0.5 + PDFBox + Claude AI

---

## 1. Etat Actuel du Pipeline OCR

### Architecture existante
```
PDF/Image --> Apache Tika (extraction texte brut)
         --> Tabula (extraction tableaux PDF)
         --> RegexExtractionService (25+ patterns: ICE, IF, RC, TVA, montants...)
         --> Claude AI (extraction structuree si regex insuffisant)
         --> ValidationService (validation metier marocaine)
         --> ERP Sage (1000/X3/50)
```

### Lacune identifiee
Tika delegue a Tesseract si disponible mais **sans preprocessing, sans tuning linguistique, sans amelioration d'image**. Pour les factures scannees ou photographiees, la precision est insuffisante.

---

## 2. Comparaison des Moteurs OCR pour JVM

### A. Tesseract via Tess4J (Recommande - Open Source)

| Critere | Detail |
|---------|--------|
| **Integration** | `net.sourceforge.tess4j:tess4j:5.13.0` (JNA natif) |
| **Langues** | 100+ dont arabe (`ara`) et francais (`fra`) simultanement |
| **Cout** | Gratuit (self-hosted) |
| **Precision** | Bonne avec preprocessing (LSTM neural network, Tesseract 5.x) |
| **Complexite** | Faible - ajout dependance + Docker config |

**Configuration optimale pour factures marocaines:**
- OEM 1 (LSTM only) pour meilleure precision
- PSM 6 (bloc uniforme) pour layouts standard, PSM 4 pour donnees tabulaires
- Langues: `fra+ara` (francais en priorite pour factures business)
- Character whitelisting pour champs numeriques (ICE, montants)
- Minimum 300 DPI
- Utiliser `tessdata_best` (pas `tessdata_fast`) pour precision maximale

**Dependances Docker:**
```dockerfile
RUN apt-get install -y tesseract-ocr tesseract-ocr-fra tesseract-ocr-ara libopencv-dev
```

### B. PaddleOCR (Meilleure Precision Open Source)

| Critere | Detail |
|---------|--------|
| **Integration** | Python sidecar microservice (pas de binding JVM) |
| **Precision** | 94.5% sur benchmarks documents (PP-OCRv5, 2025) |
| **Cout** | ~0.09$/1000 pages (self-hosted GPU) |
| **Complexite** | Elevee - microservice Python separe |

**Points forts:**
- PP-OCRv5 surpasse la plupart des solutions commerciales
- Pipeline complet: detection + reconnaissance + analyse layout
- Module PP-Structure pour extraction tableaux
- Fonctionne entierement offline

**Points faibles:**
- Pas de binding JVM natif
- Necessite Python + PaddlePaddle framework
- Ressources plus lourdes que Tesseract

### C. Azure Document Intelligence (Meilleur Cloud)

| Critere | Detail |
|---------|--------|
| **Integration** | REST API |
| **Precision** | Excellente - modele pre-entraine pour factures |
| **Cout** | 1.50-10$/1000 pages |
| **Complexite** | Moyenne - appel API REST |

**Avantages pour factures marocaines:**
- Modele pre-entraine `prebuilt-invoice` qui extrait en-tete, lignes, totaux, TVA
- Surpasse AWS Textract et Google Document AI sur benchmarks factures
- Entrainement custom possible pour identifiants marocains (ICE, IF, RC)
- Excellent support arabe
- Scores de confiance par champ

### D. Google Cloud Vision API

| Critere | Detail |
|---------|--------|
| **Integration** | REST API / client Java |
| **Precision** | Tres bonne arabe + francais |
| **Cout** | ~1.50$/1000 pages |
| **Complexite** | Moyenne |

### E. AWS Textract

| Critere | Detail |
|---------|--------|
| **Integration** | REST API / AWS SDK Java |
| **Precision** | Bonne (AnalyzeExpense API pour factures) |
| **Cout** | ~1.50$/1000 pages |
| **Support arabe** | Moins mature que Google/Azure |

### F. EasyOCR

| Critere | Detail |
|---------|--------|
| **Integration** | Python sidecar |
| **Langues** | 80+ dont arabe |
| **Precision** | Inferieure a PaddleOCR |
| **Developpement** | Moins actif que PaddleOCR |

---

## 3. Tableau Comparatif Global

| Solution | Cout/1000 pages | Qualite Arabe/Francais | Integration JVM | Offline | Factures |
|----------|----------------|----------------------|-----------------|---------|----------|
| **Tess4J** | Gratuit | Moderee | Native (JNA) | Oui | Bon avec config |
| **PaddleOCR** | ~0.09$ (GPU) | Bonne | Sidecar Python | Oui | Excellent |
| **Azure Doc Intelligence** | 1.50-10$ | Excellente | REST API | Non | Meilleur cloud |
| **Google Vision** | ~1.50$ | Tres bonne | REST API | Non | Bon |
| **AWS Textract** | ~1.50$ | Bonne | REST API | Non | Bon |
| **Claude Vision (direct)** | 3-15$ | Excellente | Deja integre | Non | Excellent |
| **EasyOCR** | Gratuit | Moderee | Sidecar Python | Oui | Moyen |

---

## 4. Preprocessing - Hooks et Techniques

Le preprocessing peut ameliorer la precision OCR de **~20%** et reduire les erreurs de reconnaissance de plus de moitie.

### Pipeline de preprocessing recommande

```
Image/Scan --> [1] Correction geometrique (deskew, perspective)
          --> [2] Amelioration image (binarisation, debruitage, contraste)
          --> [3] Normalisation resolution (min 300 DPI)
          --> [4] OCR Engine (Tesseract/PaddleOCR)
```

### Etape 1: Correction Geometrique
- **Deskewing**: Corriger rotation/inclinaison du scan (meme legere = impact significatif)
- **Correction perspective**: Pour factures photographiees
- Implementation: OpenCV Hough Transform

### Etape 2: Amelioration Image
- **Binarisation adaptive** (Otsu ou Sauvola): Conversion noir/blanc, reduit taille et met en evidence le texte
- **Debruitage**: Suppression artefacts scanner, poussieres, taches
- **Enhancement contraste**: Ameliorer visibilite texte pali/efface

### Etape 3: Normalisation Resolution
- Minimum 300 DPI obligatoire
- Upscale images basse resolution avant OCR
- PDFBox pour rendre pages PDF en images a DPI configurable

### Librairies JVM pour preprocessing
- **OpenCV Java** (`org.openpnp:opencv:4.9.0-0`): Deskew, binarisation adaptive, debruitage Gaussien
- **imgscalr**: Redimensionnement/scaling Java simple
- **PDFBox ImageIO**: Rendu PDF vers images haute resolution

---

## 5. Approche Bilingual Arabe/Francais

### Defis specifiques factures marocaines
- Scripts mixtes: arabe (RTL) et francais (LTR) sur meme document
- Formes contextuelles: lettres arabes changent selon position dans le mot
- Identifiants fiscaux: ICE (15 chiffres), IF, RC, Patente, CNSS sont numeriques dans contexte multilingue

### Strategies recommandees
1. **Tesseract**: combinaison `fra+ara`, francais en priorite (factures business majoritairement en francais)
2. **Champs numeriques**: whitelisting caracteres ou post-traitement regex (deja en place via `RegexExtractionService`)
3. **Approche deux passes**: 1ere passe `fra` pour document principal, 2eme passe `ara` ciblee sur regions arabes
4. **Cloud > Open-source** pour arabe: Google/Azure gere mieux l'arabe que Tesseract
5. **Claude AI compense les erreurs OCR**: meme du texte OCR bruite peut etre interprete correctement par le LLM

---

## 6. Architecture Recommandee (3 Tiers)

### Tier 1: Quick Win (Implementation immediate recommandee)
**Ajouter Tess4J + OpenCV preprocessing au pipeline existant**

```
PDF/Image --> OpenCV preprocessing (deskew, binarize, denoise)
          --> Tess4J (fra+ara, OEM 1, PSM 6)
          --> Merge avec extraction Tika existante
          --> RegexExtractionService (inchange)
          --> Claude AI (si extraction sparse)
          --> Validation + ERP
```

**Modifications necessaires:**
1. Ajouter `build.gradle.kts`:
   ```kotlin
   implementation("net.sourceforge.tess4j:tess4j:5.13.0")
   implementation("org.openpnp:opencv:4.9.0-0")
   ```
2. Enrichir `OcrService.kt` avec preprocessing image + Tesseract comme supplement a Tika
3. Telecharger `fra.traineddata` et `ara.traineddata` (best quality) dans image Docker
4. Mettre a jour `Dockerfile`:
   ```dockerfile
   RUN apt-get install -y tesseract-ocr tesseract-ocr-fra tesseract-ocr-ara
   ```

### Tier 2: Cloud Enhancement (Si budget disponible)
**Ajouter Azure Document Intelligence comme chemin premium optionnel**

- Utiliser modele pre-entraine `prebuilt-invoice` pour extraction haute confiance
- Fallback vers Tess4J + Claude pour traitement offline/economique
- Azure gere mieux les factures bilingues arabe/francais que toute solution open-source

### Tier 3: Precision Maximale (Future)
**Ajouter PaddleOCR comme microservice sidecar**

- Deployer PaddleOCR dans conteneur Docker separe avec API REST
- Utiliser PP-OCRv5 pour extraction texte et PP-Structure pour detection tableaux
- Router factures scannees/images vers PaddleOCR, PDFs numeriques vers Tika

---

## 7. Hooks de Processing Recommandes

### Pre-OCR Hooks
```kotlin
// Hook 1: Detection type document
fun detectDocumentType(file: InputStream): DocumentType
// -> PDF numerique, PDF scanne, Image, Photo

// Hook 2: Preprocessing conditionnel
fun preprocessForOcr(image: BufferedImage): BufferedImage
// -> Deskew, binarize, denoise selon type

// Hook 3: Detection langue dominante
fun detectPrimaryLanguage(sample: String): String
// -> "fra", "ara", "fra+ara"
```

### Post-OCR Hooks
```kotlin
// Hook 4: Nettoyage texte OCR
fun cleanOcrText(rawText: String): String
// -> Supprimer artefacts, normaliser espaces, corriger encodage

// Hook 5: Score de confiance OCR
fun calculateOcrConfidence(text: String, source: OcrSource): Double
// -> Evaluer qualite extraction pour decider si Claude AI necessaire

// Hook 6: Fallback cascade
fun ocrWithFallback(file: InputStream): OcrResult
// -> Tika -> Tess4J -> PaddleOCR -> Azure (cascade par qualite)
```

### Validation Hooks
```kotlin
// Hook 7: Cross-validation multi-engine
fun crossValidateOcr(results: Map<OcrEngine, String>): String
// -> Comparer resultats de plusieurs engines, prendre le meilleur

// Hook 8: Detection anomalies
fun detectOcrAnomalies(extracted: ExtractedInvoiceData): List<Warning>
// -> ICE invalide, montants incoherents, champs manquants
```

---

## 8. Librairies et Frameworks Specialises Factures

| Outil | Type | Specialite |
|-------|------|------------|
| **Mindee docTR** | Open-source | OCR document specifique, end-to-end |
| **Invoice2data** | Open-source Python | Templates factures, extraction regles |
| **Nanonets** | SaaS | OCR factures avec ML custom |
| **Rossum** | SaaS | IA extraction factures |
| **Veryfi** | SaaS API | OCR factures temps reel |
| **ABBYY FineReader** | Commercial | OCR entreprise, excellent arabe |

---

## 9. Conclusion et Recommandation Finale

### Action immediate: Tier 1 (Tess4J + OpenCV)
- **Impact**: +30-40% precision sur factures scannees
- **Effort**: 2-3 jours de developpement
- **Cout**: 0$ (open-source)
- **Risque**: Faible

### Le secret: l'approche hybride OCR + LLM
L'architecture actuelle avec Claude AI est deja un pattern fort. La recherche confirme que **les approches hybrides OCR + LLM surpassent chacune isolement** pour les factures. Claude compense les erreurs OCR par raisonnement contextuel. L'amelioration du pipeline OCR en amont ne fait que renforcer cette synergie.

### Points cles
1. **Tess4J** = meilleur rapport cout/integration pour JVM
2. **Preprocessing OpenCV** = amelioration immediate de 20%+ de precision
3. **Azure Document Intelligence** = meilleur cloud pour factures si budget le permet
4. **PaddleOCR** = meilleure precision open-source mais necessite sidecar Python
5. **L'approche Claude AI existante** est un avantage competitif fort a conserver et renforcer

---

## Sources

- [Spring Boot Tesseract OCR in Kotlin with multi-stage Docker](https://thepurushoths.medium.com/spring-boot-tesseract-ocr-in-kotlin-with-multi-stage-docker-515cdd13af37)
- [Optical Character Recognition with Tesseract - Baeldung](https://www.baeldung.com/java-ocr-tesseract)
- [8 Top Open-Source OCR Models Compared](https://modal.com/blog/8-top-open-source-ocr-models-compared)
- [Best OCR Models 2026: Benchmarks & Comparison](https://www.codesota.com/ocr)
- [Technical Analysis of Modern Non-LLM OCR Engines](https://intuitionlabs.ai/articles/non-llm-ocr-technologies)
- [Tess4J - JNA wrapper for Tesseract](https://github.com/nguyenq/tess4j)
- [10 Best AI OCR Tools for Invoice Extraction in 2026](https://www.koncile.ai/en/ressources/top-10-ocr-tools-for-invoices-2025)
- [Best Invoice OCR 2025: Guide to Extracting Invoice Data](https://unstract.com/blog/best-ocr-for-invoice-processing-invoice-ocr/)
- [OCR Invoice Processing: How It Works](https://www.docuclipper.com/blog/ocr-invoice-processing/)
- [Boost Tesseract OCR Accuracy: Advanced Tips](https://sparkco.ai/blog/boost-tesseract-ocr-accuracy-advanced-tips-techniques)
- [Improve OCR Accuracy with Preprocessing Tips](https://docparser.com/blog/improve-ocr-accuracy/)
- [Best Multilingual OCR Software in 2026](https://www.llamaindex.ai/blog/best-multilingual-ocr-software)
- [PaddleOCR vs Tesseract: Tested Both (2025)](https://www.codesota.com/ocr/paddleocr-vs-tesseract)
- [Open Source OCR for Invoice Extraction](https://invoicedataextraction.com/blog/open-source-ocr-invoice-extraction)
- [Using Tesseract OCR to extract scanned invoice data in Java](https://pretius.com/blog/tesseract-invoice-data-ocr/)
- [AWS Textract vs Google vs Azure: Invoice Extraction Benchmark](https://www.businesswaretech.com/blog/research-best-ai-services-for-automatic-invoice-processing)
- [Claude vs GPT vs Gemini: Invoice Extraction Comparison](https://www.koncile.ai/en/ressources/claude-gpt-or-gemini-which-is-the-best-llm-for-invoice-extraction)
- [Document Data Extraction 2026: LLMs vs OCRs](https://www.vellum.ai/blog/document-data-extraction-llms-vs-ocrs)
- [Tesseract Page Segmentation Modes Explained](https://pyimagesearch.com/2021/11/15/tesseract-page-segmentation-modes-psms-explained-how-to-improve-your-ocr-accuracy/)
- [Improving Tesseract Output Quality](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html)
