# Audit initial des controles — 2026-04-21

## Objectif
Etablir la baseline de justesse et de couverture du moteur de validation (R01-R20 + CUSTOM-XX), avant toute campagne d'optimisation fine. Sert de point de reference pour mesurer l'impact des chantiers futurs portes par le sub-agent `controls-auditor`.

## Perimetre audite
- Regles systeme : R01 a R20 definies dans `ValidationEngine.kt`.
- Regles IA : templates CUSTOM-XX geres par `CustomRuleService` en batch.
- Regles humaines : CK01 a CK10 de la checklist CCF-EN-04 (non recalculees).

## Constat structurel

### Ce qui est en place
- 22 regles systeme couvrant la concordance des montants, la coherence des identifiants fiscaux (ICE/IF/RIB), les retenues, les references croisees, les dates, la completude documentaire, et l'attestation fiscale.
- Moteur parametrable globalement et par dossier (overrides), avec cache Caffeine 5 min sur les config de regles.
- Dependances inter-regles declarees dans `RULE_DEPENDENCIES`, exploitees par `rerunRule()` pour relancer en cascade.
- Resilience4j sur les appels Claude (classification, extraction, batch CUSTOM) : circuit breaker, rate limiter, bulkhead.
- Depuis PR #68 : colonne `duration_ms` + helper `measureRule` + endpoint `/api/admin/rules/performance` pour profiler.
- Depuis PR #67 : split modeles Haiku classif / Sonnet ou Opus extract + max_tokens adaptatif.
- Depuis PR #69 : score qualite composite sur chaque extraction avec contrat MANDATORY par type de document.

### Ce qui manque / est a risque
1. **Pas de mesure du taux de faux positifs**
   - Aucune metrique automatique ne quantifie combien de fois une regle NOK a ete reclassee manuellement en OK.
   - Impossible de savoir aujourd'hui quelle regle est "bruyante" vs quelle regle est fiable.
2. **Pas de jeu de reference**
   - Aucun corpus de dossiers synthetiques dont on connaitrait a l'avance le verdict attendu.
   - La premiere iteration de PR #5 introduit 3 scenarios golden (voir plus bas). A etendre.
3. **Absence de regles anti-fraude structurelles**
   - R21 anti-doublon facture (meme numero OU meme montant+fournisseur+date +/-3j sur 12 mois) : absente.
   - R22 paiement post-reception (date OP > date PV) : absente.
   - R23 coherence TVA vs categorie service : absente.
   - R24 completude lignes facture au-dela d'un seuil montant : absente.
   - R25 separation des pouvoirs (signataire checklist != signataire facture) : absente.
4. **Pas de detection de drift temporel**
   - Si le format d'une famille de factures change (nouveau fournisseur) et que les regles commencent a echouer silencieusement, aucune alerte.
5. **Tolerances non documentees formellement**
   - `app.tolerance-montant = 5%` est utilise partout ; pas d'analyse par type de dossier / type de ligne.

## KPIs cibles (a alimenter)

| KPI | Source | Cible |
|-----|--------|-------|
| Taux de faux positifs par regle | `statutOriginal IS NOT NULL AND statut = CONFORME` | < 20% |
| Couverture metier | check-list des cas (voir plus haut) | 100% des cas listes |
| Concordance Systeme vs IA | diff `statut` pour regles equivalentes | > 90% |
| Nb de dossiers golden | `src/test/resources/golden-dossiers/` | >= 15 a terme |
| Drift par regle | variation taux NOK mois N vs N-3 | < 15% |

## Jeu de dossiers golden (initial)
Cette PR introduit 3 scenarios de regression dans `GoldenDossiersRegressionTest` :

1. **Dossier BC parfait** : tous montants concordants, ICE identiques, dates coherentes, lignes arithmetiquement exactes -> tous les R doivent etre CONFORME ou NON_APPLICABLE.
2. **Incoherence arithmetique HT + TVA != TTC** : facture avec montants incoherents -> R16 doit etre NON_CONFORME.
3. **Attestation fiscale expiree** : ARF datee > 6 mois -> R18 doit etre NON_CONFORME.

A etendre progressivement par le sub-agent `controls-auditor` avec : ICE different, OP superieur au montant attendu, double paiement, TVA incoherente par categorie, paiement avant PV.

## Recommandations prioritaires (par ordre de ROI)

1. **Completer l'instrumentation `duration_ms`** sur les 28 regles restantes via `measureRule`. Transformation mecanique, 1 ligne par regle. Cela permettra d'identifier les vrais points chauds du moteur.
2. **Etendre le jeu de dossiers golden** a >= 10 cas reels pour sanctuariser la justesse avant toute refonte.
3. **Implementer R21 anti-doublon** : priorite haute, risque de paiement multiple reel.
4. **Ajouter tableau de bord faux positifs** dans Settings (admin) : afficher par regle le ratio `statutOriginal!=null` / total NOK.
5. **R22 paiement post-reception** : verification simple, forte valeur metier.

## Prochain rendez-vous d'audit
Dans 30 jours (2026-05-21) apres avoir :
- instrumente les regles,
- elargit le jeu golden,
- collecte des donnees de faux positifs.

Fichier genere par le sub-agent `controls-auditor` (voir `.claude/agents/controls-auditor.md`).
