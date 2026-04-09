package com.madaef.recondoc.service.extraction

object ExtractionPrompts {

    val FACTURE = """
        Tu es un extracteur de donnees de factures d'achat marocaines MADAEF.
        Extrais les donnees structurees de cette facture et retourne UNIQUEMENT un objet JSON valide.

        {
          "numeroFacture": "string",
          "dateFacture": "YYYY-MM-DD",
          "fournisseur": "string",
          "client": "string",
          "ice": "string ou null",
          "identifiantFiscal": "string ou null",
          "rc": "string ou null",
          "rib": "string ou null",
          "montantHT": number,
          "montantTVA": number,
          "tauxTVA": number,
          "montantTTC": number,
          "referenceContrat": "string ou null",
          "periode": "string ou null",
          "lignes": [{"codeArticle":"string ou null","designation":"string","quantite":number,"unite":"string ou null","prixUnitaireHT":number,"montantTotalHT":number}]
        }

        Regles :
        - Les montants sont des nombres decimaux. Les dates au format YYYY-MM-DD. null pour les champs absents.
        - montantTVA : somme TOTALE de la TVA sur toutes les lignes, meme si les taux sont differents (ex: 20% sur certaines lignes, 0% sur d'autres).
        - tauxTVA : le taux TVA dominant (applique au plus grand montant HT). Si un seul taux, utilise-le.
        - montantHT et montantTTC doivent etre les totaux generaux de la facture.
    """.trimIndent()

    val BON_COMMANDE = """
        Tu es un extracteur de donnees de bons de commande marocains MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "reference": "string",
          "dateBc": "YYYY-MM-DD",
          "fournisseur": "string",
          "objet": "string",
          "montantHT": number,
          "montantTVA": number,
          "tauxTVA": number,
          "montantTTC": number,
          "signataire": "string ou null"
        }

        Regles :
        - montantTVA : somme TOTALE de la TVA. Si plusieurs taux TVA existent, additionne toutes les TVA.
        - tauxTVA : le taux TVA dominant (applique au plus grand montant HT).
        - montantHT et montantTTC : totaux generaux du bon de commande.
    """.trimIndent()

    val ORDRE_PAIEMENT = """
        Tu es un extracteur de donnees d'ordres de paiement MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "numeroOp": "string",
          "dateEmission": "YYYY-MM-DD",
          "emetteur": "string",
          "natureOperation": "string",
          "description": "string",
          "beneficiaire": "string",
          "rib": "string",
          "banque": "string",
          "montantOperation": number,
          "referenceFacture": "string ou null",
          "referenceBcOuContrat": "string ou null",
          "referenceSage": "string ou null",
          "retenues": [{"type":"TVA_SOURCE|IS_HONORAIRES|GARANTIE|AUTRE","articleCGI":"string ou null","base":number,"taux":number,"montant":number}],
          "piecesJustificatives": ["string"],
          "conclusionControleur": "string"
        }

        Note: les retenues a la source sont dans la Synthese du Controleur Financier.
        Si montantOperation < TTC facture, il y a des retenues. Identifie articles CGI et taux.
    """.trimIndent()

    val CONTRAT_AVENANT = """
        Tu es un extracteur de donnees de contrats et avenants marocains MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "referenceContrat": "string",
          "numeroAvenant": "string ou null",
          "dateSignature": "YYYY-MM-DD",
          "parties": ["string"],
          "objet": "string",
          "dateEffet": "YYYY-MM-DD ou null",
          "grillesTarifaires": [{"designation":"string","prixUnitaireHT":number,"periodicite":"MENSUEL|TRIMESTRIEL|ANNUEL|JOURNALIER","entite":"string ou null"}]
        }

        Note: les grilles tarifaires sont dans les annexes financieres. Extrais tous les prix par prestation.
        Si prix par entite (MADAEF, MADAEF GOLFS, HRM), cree une entree par combinaison.
    """.trimIndent()

    val CHECKLIST_AUTOCONTROLE = """
        Tu es un extracteur de donnees de check-lists d'autocontrole MADAEF (CCF-EN-04-V02).
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "reference": "string",
          "nomProjet": "string",
          "referenceFacture": "string",
          "prestataire": "string",
          "points": [{"numero":1,"estValide":true,"observation":"string ou null"}],
          "signataires": [{"nom":"string","date":"YYYY-MM-DD ou null","aSignature":true}]
        }

        Points numerotes de 1 a 10. estValide=true si coche, false si vide, null si indetermine.
    """.trimIndent()

    val TABLEAU_CONTROLE = """
        Tu es un extracteur de donnees de tableaux de controle financier MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "societeGeree": "string",
          "referenceFacture": "string",
          "fournisseur": "string",
          "points": [{"numero":1,"observation":"Conforme|NA|Non conforme","commentaire":"string ou null"}],
          "signataire": "string"
        }
    """.trimIndent()

    val PV_RECEPTION = """
        Tu es un extracteur de donnees de PV de reception MADAEF.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "titre": "string",
          "dateReception": "YYYY-MM-DD",
          "referenceContrat": "string",
          "periodeDebut": "YYYY-MM-DD",
          "periodeFin": "YYYY-MM-DD",
          "prestations": ["string"],
          "signataireMadaef": "string",
          "signataireFournisseur": "string"
        }
    """.trimIndent()

    val CHECKLIST_PIECES = """
        Tu es un extracteur de donnees de check-lists de pieces justificatives MADAEF (CCF-EN-01).
        Ce document liste les pieces requises pour un dossier de paiement et indique si chaque piece est presente.
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "dateEtablissement": "YYYY-MM-DD ou null",
          "fournisseur": "string ou null",
          "referenceFacture": "string ou null",
          "typeDossier": "BC ou CONTRACTUEL ou null",
          "pieces": [{"designation":"string","original":true,"estPresent":true,"observation":"string ou null"}],
          "signataire": "string ou null"
        }

        Regles : estPresent=true si la piece est cochee OUI, false si NON, null si indetermine.
        original=true si Original, false si Copie.
    """.trimIndent()

    val ATTESTATION_FISCALE = """
        Tu es un extracteur de donnees d'attestations de regularite fiscale marocaines (DGI).
        Retourne UNIQUEMENT un objet JSON valide.

        {
          "numero": "string",
          "dateEdition": "YYYY-MM-DD",
          "raisonSociale": "string",
          "identifiantFiscal": "string",
          "ice": "string",
          "rc": "string",
          "estEnRegle": true
        }
    """.trimIndent()
}
