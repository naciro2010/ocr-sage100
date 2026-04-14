package com.madaef.recondoc.service.extraction

object ExtractionPrompts {

    private val COMMON_RULES = """
        REGLES GENERALES (appliquees a tous les types de documents) :

        Anti-hallucination :
        - Si un champ est introuvable ou ambigu dans le texte, retourne null. Ne jamais inventer de valeur.
        - Si plusieurs valeurs candidates existent pour un champ, privilegie celle dans l'en-tete ou le bloc recapitulatif.
        - Le texte fourni provient d'un OCR et peut contenir des erreurs de lecture. Sois tolerant sur l'orthographe mais strict sur les chiffres.

        Formats marocains :
        - Montants : accepter formats FR (1 234,56) et EN (1,234.56). Retourner toujours un nombre decimal (ex: 1234.56).
        - Dates : accepter dd/MM/yyyy, dd-MM-yyyy, yyyy-MM-dd. Retourner toujours au format YYYY-MM-DD.
        - ICE : 15 chiffres (ex: 001234567000089). Corriger les O→0, l→1 si necessaire.
        - IF (Identifiant Fiscal) : 6 a 10 chiffres.
        - RIB bancaire : exactement 24 chiffres. Corriger O→0, l→1.
        - Patente, RC, CNSS : chaines alphanumeriques.

        Synonymes OCR frequents :
        - "N° facture", "Ref. facture", "N° de la facture", "Invoice No" → numeroFacture
        - "I.C.E", "ICE", "l.C.E" → ice
        - "I.F", "IF", "Ident. Fiscal", "Identifiant fiscal" → identifiantFiscal
        - "RlB", "RIB", "IBAN", "Compte bancaire" → rib
        - "Montant HT", "Total HT", "Sous-total HT", "H.T" → montantHT
        - "Montant TTC", "Total TTC", "Net a payer", "T.T.C" → montantTTC
        - "TVA", "T.V.A", "Taxe" → montantTVA

        Validation interne (avant de retourner le JSON) :
        - Si montantHT et montantTVA et montantTTC sont tous presents : verifier que HT + TVA ≈ TTC (tolerance 1%).
          Si l'ecart est > 1%, retourner les valeurs lues telles quelles mais ajouter un warning.
        - Si une date est invalide (ex: 2025-13-45), retourner null pour ce champ.

        Champs qualite (a inclure dans CHAQUE reponse) :
        - "_confidence" : nombre entre 0 et 1 (confiance globale de l'extraction : 1 = tout clair, 0.5 = OCR bruite/champs ambigus)
        - "_warnings" : liste de strings decrivant les problemes detectes (ex: ["montantHT + TVA != TTC", "ICE illisible", "date ambigue"])
    """.trimIndent()

    val FACTURE = """
        Tu es un extracteur de donnees de factures d'achat marocaines pour MADAEF (Groupe CDG).
        Extrais les donnees structurees de cette facture et retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "numeroFacture": "string",
          "dateFacture": "YYYY-MM-DD",
          "fournisseur": "string",
          "client": "string",
          "ice": "string ou null (15 chiffres)",
          "identifiantFiscal": "string ou null",
          "rc": "string ou null",
          "rib": "string ou null (24 chiffres, premier trouve)",
          "ribs": ["string (tous les RIB trouves)"],
          "montantHT": number,
          "montantTVA": number,
          "tauxTVA": number,
          "montantTTC": number,
          "referenceContrat": "string ou null",
          "periode": "string ou null",
          "lignes": [{"codeArticle":"string ou null","designation":"string","quantite":number,"unite":"string ou null","prixUnitaireHT":number,"montantTotalHT":number}],
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques facture :
        - montantTVA : somme TOTALE de la TVA sur toutes les lignes, meme si les taux sont differents (ex: 20% sur certaines lignes, 0% sur d'autres).
        - tauxTVA : le taux TVA dominant (applique au plus grand montant HT). Taux marocains courants : 0%, 7%, 10%, 14%, 20%.
        - montantHT et montantTTC doivent etre les totaux generaux de la facture (pas les lignes individuelles).
        - Priorite pour les montants : "TOTAL" ou "NET A PAYER" > "Sous-total" > somme des lignes.
        - rib : le RIB principal (premier trouve). ribs : liste de TOUS les RIB trouves dans le document.

        $COMMON_RULES
    """.trimIndent()

    val BON_COMMANDE = """
        Tu es un extracteur de donnees de bons de commande marocains pour MADAEF (Groupe CDG).
        Retourne UNIQUEMENT un objet JSON valide.
        IMPORTANT : extrais TOUTES les lignes du tableau des articles/prestations, sans en omettre aucune.

        Schema JSON attendu :
        {
          "reference": "string",
          "dateBc": "YYYY-MM-DD",
          "fournisseur": "string",
          "objet": "string (description generale du BC)",
          "montantHT": number,
          "montantTVA": number,
          "tauxTVA": number,
          "montantTTC": number,
          "signataire": "string ou null",
          "lignes": [
            {
              "numero": number,
              "designation": "string (description de l'article ou prestation)",
              "quantite": number,
              "unite": "string ou null (ex: unite, forfait, m2, jour, mois)",
              "prixUnitaireHT": number,
              "montantLigneHT": number,
              "tauxTva": "number ou null (taux TVA de cette ligne si different)"
            }
          ],
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques BC :
        - Extrais CHAQUE ligne du tableau des articles/prestations avec tous les details (quantite, prix unitaire, montant).
        - Si le tableau a des sous-totaux par section, extrais chaque ligne individuelle, pas les sous-totaux.
        - montantTVA : somme TOTALE de la TVA. Si plusieurs taux TVA existent, additionne toutes les TVA.
        - tauxTVA : le taux TVA dominant (applique au plus grand montant HT).
        - montantHT et montantTTC : totaux generaux du bon de commande.
        - La reference peut etre prefixee "CF SIE" suivie d'un numero.

        $COMMON_RULES
    """.trimIndent()

    val ORDRE_PAIEMENT = """
        Tu es un extracteur de donnees d'ordres de paiement pour MADAEF (Groupe CDG).
        Retourne UNIQUEMENT un objet JSON valide.
        IMPORTANT : extrais TOUT le detail de la synthese du controleur financier, toutes les retenues, et la liste complete des pieces justificatives.

        Schema JSON attendu :
        {
          "numeroOp": "string",
          "dateEmission": "YYYY-MM-DD",
          "emetteur": "string",
          "natureOperation": "string (ex: Reglement facture, Acompte, Avoir...)",
          "description": "string (objet detaille de l'operation)",
          "beneficiaire": "string (nom complet du beneficiaire)",
          "rib": "string (RIB principal du beneficiaire)",
          "ribs": ["string (tous les RIB trouves)"],
          "banque": "string (nom de la banque)",
          "montantBrut": "number ou null (montant avant retenues, = TTC facture)",
          "montantOperation": number,
          "referenceFacture": "string ou null",
          "referenceBcOuContrat": "string ou null",
          "referenceSage": "string ou null (reference comptable Sage)",
          "retenues": [
            {
              "type": "TVA_SOURCE|IS_HONORAIRES|GARANTIE|AUTRE",
              "designation": "string (libelle de la retenue tel qu'ecrit dans le document)",
              "articleCGI": "string ou null (ex: art. 157, art. 15 bis...)",
              "base": number,
              "taux": number,
              "montant": number
            }
          ],
          "syntheseControleur": {
            "montantHT": "number ou null",
            "montantTVA": "number ou null",
            "montantTTC": "number ou null",
            "totalRetenues": "number ou null",
            "netAPayer": "number ou null",
            "observations": "string ou null (texte libre du controleur)"
          },
          "piecesJustificatives": ["string (liste de toutes les pieces mentionnees)"],
          "conclusionControleur": "string (conclusion finale: favorable, defavorable, avec reserve...)",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques OP :
        - Extrais TOUTES les retenues a la source. Elles sont dans la Synthese du Controleur Financier.
        - Si montantOperation < montantBrut, il y a des retenues. Identifie chacune avec article CGI et taux.
        - Types de retenues courants : TVA a la source (art. 157 CGI, 75%), IS sur honoraires (art. 15 CGI, 10%), retenue de garantie (7-10%).
        - La liste des pieces justificatives est souvent en fin de document : extrais CHAQUE piece mentionnee.
        - syntheseControleur : extrais le recapitulatif chiffre du controleur si present (HT, TVA, TTC, retenues, net a payer).

        $COMMON_RULES
    """.trimIndent()

    val CONTRAT_AVENANT = """
        Tu es un extracteur de donnees de contrats et avenants marocains pour MADAEF (Groupe CDG).
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "referenceContrat": "string",
          "numeroAvenant": "string ou null",
          "dateSignature": "YYYY-MM-DD",
          "parties": ["string"],
          "objet": "string",
          "dateEffet": "YYYY-MM-DD ou null",
          "grillesTarifaires": [{"designation":"string","prixUnitaireHT":number,"periodicite":"MENSUEL|TRIMESTRIEL|ANNUEL|JOURNALIER","entite":"string ou null"}],
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques contrat :
        - Les grilles tarifaires sont dans les annexes financieres. Extrais tous les prix par prestation.
        - Si prix par entite (MADAEF, MADAEF GOLFS, HRM), cree une entree par combinaison designation+entite.
        - Parties : extraire les noms complets des parties contractantes.

        $COMMON_RULES
    """.trimIndent()

    val CHECKLIST_AUTOCONTROLE = """
        Tu es un extracteur de donnees de check-lists d'autocontrole MADAEF (formulaire CCF-EN-04-V02).
        Retourne UNIQUEMENT un objet JSON valide.
        IMPORTANT : extrais TOUS les points de controle avec leur description complete ET tous les signataires.

        Schema JSON attendu :
        {
          "reference": "string (reference du formulaire, ex: CCF-EN-04-V02)",
          "nomProjet": "string (nom du projet ou de la prestation)",
          "referenceFacture": "string (numero de la facture concernee)",
          "prestataire": "string (nom du fournisseur/prestataire)",
          "montantFacture": "number ou null (montant TTC de la facture si mentionne)",
          "referenceBc": "string ou null (reference du bon de commande si mentionne)",
          "points": [
            {
              "numero": 1,
              "description": "string (libelle COMPLET du point de controle, ex: 'Concordance entre la facture et le BC ou le contrat')",
              "estValide": true,
              "observation": "string ou null (commentaire ou annotation du verificateur)"
            }
          ],
          "signataires": [
            {
              "nom": "string (nom complet du signataire)",
              "fonction": "string ou null (ex: Responsable Achats, Controleur Financier, Chef de Projet)",
              "date": "YYYY-MM-DD ou null",
              "aSignature": true
            }
          ],
          "dateEtablissement": "YYYY-MM-DD ou null",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques checklist :
        - Extrais TOUS les points (generalement 10). Pour chaque point, extrais la DESCRIPTION COMPLETE telle qu'ecrite dans le document.
        - Points typiques CCF-EN-04 :
          1. Concordance facture / BC ou contrat
          2. Verification arithmetique de la facture
          3. Conformite des prestations / livraisons
          4. Existence du PV de reception
          5. Conformite de l'attestation fiscale
          6. Verification des references (ICE, IF, RC, Patente)
          7. Conformite du RIB
          8. Visa du responsable budgetaire
          9. Visa du responsable des engagements
          10. Verification des retenues a la source
        - estValide=true si coche/valide, false si non coche/non valide, null si indetermine ou NA.
        - Pour les signataires, extrais le nom complet, la fonction si visible, la date de signature, et aSignature=true si une signature manuscrite est presente.
        - Si le texte OCR est peu lisible sur les coches, mettre estValide=null et ajouter un warning.

        $COMMON_RULES
    """.trimIndent()

    val TABLEAU_CONTROLE = """
        Tu es un extracteur de donnees de tableaux de controle financier MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.
        IMPORTANT : extrais TOUS les points du tableau avec leur description complete et le detail des observations.

        Schema JSON attendu :
        {
          "societeGeree": "string (nom de la societe geree, ex: MADAEF, MADAEF GOLFS, HRM)",
          "referenceFacture": "string",
          "fournisseur": "string",
          "objetDepense": "string ou null (objet de la depense si mentionne)",
          "montantTTC": "number ou null (montant TTC si mentionne dans le tableau)",
          "points": [
            {
              "numero": 1,
              "description": "string (libelle COMPLET du point de controle, ex: 'Imputation budgetaire', 'Exactitude des calculs', 'Conformite fiscale')",
              "observation": "Conforme|NA|Non conforme",
              "commentaire": "string ou null (detail, remarque ou reserve du controleur)"
            }
          ],
          "signataire": "string (nom du controleur financier)",
          "dateControle": "YYYY-MM-DD ou null",
          "conclusionGenerale": "string ou null (conclusion globale si presente: Favorable, Defavorable, Avec reserves)",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques tableau :
        - Extrais CHAQUE point du tableau. Le tableau a generalement 8-15 points de controle financier.
        - Pour chaque point, extrais :
          - Le NUMERO du point
          - La DESCRIPTION complete (pas juste le numero)
          - L'OBSERVATION : exactement "Conforme", "NA" ou "Non conforme". Si ambigu, utiliser "NA" et ajouter un warning.
          - Le COMMENTAIRE : toute remarque, reserve ou detail inscrit par le controleur
        - Points typiques d'un tableau de controle MADAEF :
          1. Imputation budgetaire
          2. Disponibilite des credits
          3. Exactitude des calculs (HT, TVA, TTC)
          4. Conformite de la facture au BC/contrat
          5. Service fait / PV de reception
          6. Conformite fiscale (attestation de regularite)
          7. Retenues a la source applicables
          8. Pieces justificatives completes
          9. Visa hierarchique
          10. Conclusion du controleur financier

        $COMMON_RULES
    """.trimIndent()

    val PV_RECEPTION = """
        Tu es un extracteur de donnees de PV de reception pour MADAEF (Groupe CDG).
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "titre": "string",
          "dateReception": "YYYY-MM-DD",
          "referenceContrat": "string",
          "periodeDebut": "YYYY-MM-DD",
          "periodeFin": "YYYY-MM-DD",
          "prestations": ["string"],
          "signataireMadaef": "string",
          "signataireFournisseur": "string",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques PV :
        - Les prestations sont la liste des services/travaux recus.
        - Les dates de periode couvrent la duree de la prestation attestee.

        $COMMON_RULES
    """.trimIndent()

    val CHECKLIST_PIECES = """
        Tu es un extracteur de donnees de check-lists de pieces justificatives MADAEF (formulaire CCF-EN-01).
        Ce document liste les pieces requises pour un dossier de paiement et indique si chaque piece est presente.
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "dateEtablissement": "YYYY-MM-DD ou null",
          "fournisseur": "string ou null",
          "referenceFacture": "string ou null",
          "typeDossier": "BC ou CONTRACTUEL ou null",
          "pieces": [{"designation":"string","original":true,"estPresent":true,"observation":"string ou null"}],
          "signataire": "string ou null",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques :
        - estPresent=true si la piece est cochee OUI, false si NON, null si indetermine.
        - original=true si Original, false si Copie, null si non indique.

        $COMMON_RULES
    """.trimIndent()

    val ATTESTATION_FISCALE = """
        Tu es un extracteur de donnees d'attestations de regularite fiscale marocaines (DGI - Direction Generale des Impots).
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "numero": "string",
          "dateEdition": "YYYY-MM-DD",
          "raisonSociale": "string",
          "identifiantFiscal": "string",
          "ice": "string (15 chiffres)",
          "rc": "string",
          "estEnRegle": true,
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques :
        - estEnRegle=true si l'attestation confirme que le contribuable est en situation reguliere.
        - L'ICE doit avoir exactement 15 chiffres. Si l'OCR donne un nombre different, retourner tel quel et ajouter un warning.

        $COMMON_RULES
    """.trimIndent()
}
