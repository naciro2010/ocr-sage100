package com.madaef.recondoc.dto.dossier

import java.math.BigDecimal
import java.time.LocalDateTime

data class FournisseurSummaryResponse(
    val nom: String,
    val ice: String?,
    val identifiantFiscal: String?,
    val rib: String?,
    val nbDossiers: Long,
    val nbBrouillons: Long,
    val nbEnVerification: Long,
    val nbValides: Long,
    val nbRejetes: Long,
    val montantTotalTtc: BigDecimal,
    val montantValide: BigDecimal,
    val dernierDossier: LocalDateTime?,
    val premierDossier: LocalDateTime?
)

data class FournisseurDetailResponse(
    val nom: String,
    val ice: String?,
    val identifiantFiscal: String?,
    val rc: String?,
    val rib: String?,
    val nbDossiers: Long,
    val nbBrouillons: Long,
    val nbEnVerification: Long,
    val nbValides: Long,
    val nbRejetes: Long,
    val montantTotalTtc: BigDecimal,
    val montantTotalHt: BigDecimal,
    val montantTotalTva: BigDecimal,
    val montantValide: BigDecimal,
    val montantEnCours: BigDecimal,
    val dernierDossier: LocalDateTime?,
    val premierDossier: LocalDateTime?,
    val dossiers: List<DossierListResponse>
)

data class FournisseursStatsResponse(
    val totalFournisseurs: Long,
    val fournisseursActifs: Long,
    val montantTotalEngage: BigDecimal,
    val topFournisseurs: List<FournisseurSummaryResponse>
)
