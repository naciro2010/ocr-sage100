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

        Schema JSON attendu :
        {
          "reference": "string",
          "dateBc": "YYYY-MM-DD",
          "fournisseur": "string",
          "objet": "string",
          "montantHT": number,
          "montantTVA": number,
          "tauxTVA": number,
          "montantTTC": number,
          "signataire": "string ou null",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques BC :
        - montantTVA : somme TOTALE de la TVA. Si plusieurs taux TVA existent, additionne toutes les TVA.
        - tauxTVA : le taux TVA dominant (applique au plus grand montant HT).
        - montantHT et montantTTC : totaux generaux du bon de commande.
        - La reference peut etre prefixee "CF SIE" suivie d'un numero.

        $COMMON_RULES
    """.trimIndent()

    val ORDRE_PAIEMENT = """
        Tu es un extracteur de donnees d'ordres de paiement pour MADAEF (Groupe CDG).
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "numeroOp": "string",
          "dateEmission": "YYYY-MM-DD",
          "emetteur": "string",
          "natureOperation": "string",
          "description": "string",
          "beneficiaire": "string",
          "rib": "string",
          "ribs": ["string"],
          "banque": "string",
          "montantOperation": number,
          "referenceFacture": "string ou null",
          "referenceBcOuContrat": "string ou null",
          "referenceSage": "string ou null",
          "retenues": [{"type":"TVA_SOURCE|IS_HONORAIRES|GARANTIE|AUTRE","articleCGI":"string ou null","base":number,"taux":number,"montant":number}],
          "piecesJustificatives": ["string"],
          "conclusionControleur": "string",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques OP :
        - Les retenues a la source sont dans la Synthese du Controleur Financier.
        - Si montantOperation < TTC facture, il y a des retenues. Identifie articles CGI et taux.
        - Types de retenues courants : TVA a la source (art. 157 CGI), IS sur honoraires (art. 15 CGI), retenue de garantie.

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

        Schema JSON attendu :
        {
          "reference": "string",
          "nomProjet": "string",
          "referenceFacture": "string",
          "prestataire": "string",
          "points": [{"numero":1,"description":"string","estValide":true,"observation":"string ou null"}],
          "signataires": [{"nom":"string","date":"YYYY-MM-DD ou null","aSignature":true}],
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques checklist :
        - Points numerotes de 1 a 10. Extrais la description de chaque point (ex: "Concordance facture / BC", "Verification arithmetique", etc.)
        - estValide=true si coche/valide, false si non coche/non valide, null si indetermine ou NA.
        - Pour les signataires, extrais le nom complet, la date de signature si presente, et aSignature=true si la signature est apposee.
        - Si le texte OCR est peu lisible sur les coches, mettre estValide=null et ajouter un warning.

        $COMMON_RULES
    """.trimIndent()

    val TABLEAU_CONTROLE = """
        Tu es un extracteur de donnees de tableaux de controle financier MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        Schema JSON attendu :
        {
          "societeGeree": "string",
          "referenceFacture": "string",
          "fournisseur": "string",
          "points": [{"numero":1,"observation":"Conforme|NA|Non conforme","commentaire":"string ou null"}],
          "signataire": "string",
          "_confidence": number,
          "_warnings": ["string"]
        }

        Regles specifiques tableau :
        - observation doit etre exactement "Conforme", "NA" ou "Non conforme". Si ambigu, utiliser "NA" et ajouter un warning.
        - Extraire tous les points de controle numerotes.

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
