package com.madaef.recondoc.repository.dossier

import com.madaef.recondoc.entity.dossier.FournisseurAlias
import com.madaef.recondoc.entity.dossier.FournisseurCanonique
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface FournisseurCanoniqueRepository : JpaRepository<FournisseurCanonique, UUID> {
    fun findByNomNormalise(nomNormalise: String): FournisseurCanonique?
    fun findByIce(ice: String): FournisseurCanonique?

    @Query("SELECT fc FROM FournisseurCanonique fc ORDER BY fc.dateMiseAJour DESC")
    fun findAllOrderedByDerniereUtilisation(): List<FournisseurCanonique>
}

interface FournisseurAliasRepository : JpaRepository<FournisseurAlias, UUID> {
    fun findByNomNormalise(nomNormalise: String): FournisseurAlias?
    fun findByCanoniqueId(canoniqueId: UUID): List<FournisseurAlias>
    fun findByRequiresReviewTrueOrderByDateCreationDesc(): List<FournisseurAlias>
}
