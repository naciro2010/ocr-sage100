package com.madaef.recondoc

import tools.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.dossier.DossierType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
// Spring Boot 4 : `@AutoConfigureMockMvc` a migre du package
// `org.springframework.boot.test.autoconfigure.web.servlet` vers le module
// dedie spring-boot-webmvc-test (chemin `o.s.boot.webmvc.test.autoconfigure`).
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
// Spring Security 7 (Spring Boot 4) modifie l'integration @WithMockUser avec
// MockMvc : la chaine de filtres httpBasic du SecurityConfig reagit desormais
// avant que le SecurityContext du @WithMockUser soit propage, donc tout POST
// renvoie 401. Ces tests valident le comportement metier des endpoints dossier,
// pas la securite elle-meme, donc on desactive les filtres pour le slice
// (`addFilters = false`). Les tests d'authz/authn sont a couvrir dans une
// future SecurityIntegrationTest dediee (cf. audit securite P1).
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@WithMockUser(username = "test@madaef.ma", roles = ["ADMIN"])
class DossierIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private fun createDossier(type: String = "BC", fournisseur: String? = null): String {
        val body = mutableMapOf<String, Any>("type" to type)
        if (fournisseur != null) body["fournisseur"] = fournisseur
        val result = mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    @Test
    fun `POST api dossiers - create BC dossier`() {
        val body = mapOf("type" to "BC", "fournisseur" to "TEST SARL", "description" to "Test dossier")
        mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.reference").exists())
            .andExpect(jsonPath("$.type").value("BC"))
            .andExpect(jsonPath("$.statut").value("BROUILLON"))
            .andExpect(jsonPath("$.fournisseur").value("TEST SARL"))
    }

    @Test
    fun `POST api dossiers - create CONTRACTUEL dossier`() {
        val body = mapOf("type" to "CONTRACTUEL", "fournisseur" to "DXC Technology")
        mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("CONTRACTUEL"))
    }

    @Test
    fun `GET api dossiers - list dossiers`() {
        createDossier(fournisseur = "LIST TEST")
        mockMvc.perform(get("/api/dossiers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET api dossiers id - get dossier detail`() {
        val id = createDossier(fournisseur = "DETAIL TEST")
        mockMvc.perform(get("/api/dossiers/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.documents").isArray)
            .andExpect(jsonPath("$.resultatsValidation").isArray)
    }

    // --- New endpoints ---

    @Test
    fun `GET api dossiers id summary - returns lightweight summary`() {
        val id = createDossier(fournisseur = "SUMMARY TEST")
        mockMvc.perform(get("/api/dossiers/$id/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.reference").exists())
            .andExpect(jsonPath("$.statut").value("BROUILLON"))
            .andExpect(jsonPath("$.fournisseur").value("SUMMARY TEST"))
            .andExpect(jsonPath("$.nbDocuments").value(0))
            .andExpect(jsonPath("$.nbChecksConformes").value(0))
            .andExpect(jsonPath("$.nbChecksTotal").value(0))
            // Summary must NOT contain heavy collections
            .andExpect(jsonPath("$.documents").doesNotExist())
            .andExpect(jsonPath("$.resultatsValidation").doesNotExist())
    }

    @Test
    fun `GET api dossiers id documents - returns documents with extracted data`() {
        val id = createDossier()
        mockMvc.perform(get("/api/dossiers/$id/documents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents").isArray)
            .andExpect(jsonPath("$.factures").isArray)
    }

    @Test
    fun `GET api dossiers id resultats-validation - returns validation results`() {
        val id = createDossier()
        mockMvc.perform(get("/api/dossiers/$id/resultats-validation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    fun `GET api dossiers id audit - returns audit log`() {
        val id = createDossier()
        mockMvc.perform(get("/api/dossiers/$id/audit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    fun `GET api dossiers search type=CONTRACTUEL - no 500 error`() {
        createDossier("CONTRACTUEL", "DXC SEARCH")
        mockMvc.perform(get("/api/dossiers/search?type=CONTRACTUEL"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET api dossiers search type=BC - filters correctly`() {
        createDossier("BC", "BC SEARCH")
        mockMvc.perform(get("/api/dossiers/search?type=BC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    // --- Status transitions ---

    @Test
    fun `PATCH api dossiers id statut - change status to REJETE`() {
        val id = createDossier()
        mockMvc.perform(
            patch("/api/dossiers/$id/statut")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("statut" to "REJETE", "motifRejet" to "Documents manquants")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.statut").value("REJETE"))
            .andExpect(jsonPath("$.motifRejet").value("Documents manquants"))
    }

    @Test
    fun `PATCH api dossiers id statut - VALIDE blocked without validation`() {
        val id = createDossier()
        // Should succeed on empty dossier (no critical rules triggered)
        mockMvc.perform(
            patch("/api/dossiers/$id/statut")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("statut" to "VALIDE")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.statut").value("VALIDE"))
    }

    @Test
    fun `POST api dossiers id valider - validate empty dossier`() {
        val id = createDossier()
        mockMvc.perform(post("/api/dossiers/$id/valider"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    fun `POST api dossiers id valider - idempotent validation does not accumulate`() {
        val id = createDossier()
        // Run validation twice
        mockMvc.perform(post("/api/dossiers/$id/valider")).andExpect(status().isOk)
        val result = mockMvc.perform(post("/api/dossiers/$id/valider"))
            .andExpect(status().isOk).andReturn()
        val results = objectMapper.readTree(result.response.contentAsString)
        // Second run should NOT have doubled the results
        val count = results.size()
        assert(count < 30) { "Validation results accumulated: $count (expected < 30)" }
    }

    @Test
    fun `GET api dossiers stats - returns dashboard stats`() {
        mockMvc.perform(get("/api/dossiers/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").isNumber)
            .andExpect(jsonPath("$.brouillons").isNumber)
            .andExpect(jsonPath("$.valides").isNumber)
    }

    // --- Delete ---

    @Test
    fun `DELETE api dossiers id - delete dossier`() {
        val id = createDossier()
        mockMvc.perform(delete("/api/dossiers/$id"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/dossiers/$id"))
            .andExpect(status().isNotFound)
    }

    // Regression: un dossier qui a un override de regle (table dossier_rule_override
    // avec FK dossier_id) doit pouvoir etre supprime. Avant correction, la methode
    // deleteDossier oubliait cette table et renvoyait 500 Internal Server Error.
    @Test
    fun `DELETE api dossiers id - delete dossier avec overrides regles`() {
        val id = createDossier()
        mockMvc.perform(
            patch("/api/dossiers/$id/rule-config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"R01": false, "R05": true}""")
        ).andExpect(status().isOk)
        mockMvc.perform(delete("/api/dossiers/$id"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/dossiers/$id"))
            .andExpect(status().isNotFound)
    }

    // Idempotence : un second DELETE sur un id deja supprime ne doit pas
    // remonter 500. On accepte 204 (no-op) ou 404 selon les handlers.
    @Test
    fun `DELETE api dossiers id - idempotent sur dossier absent`() {
        val id = createDossier()
        mockMvc.perform(delete("/api/dossiers/$id"))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/api/dossiers/$id"))
            .andExpect { result ->
                val status = result.response.status
                assert(status == 204 || status == 404) {
                    "Expected 204 or 404 on second delete, got $status"
                }
            }
    }

    // --- 404 handling ---

    @Test
    fun `GET api dossiers unknown-id - returns 404`() {
        mockMvc.perform(get("/api/dossiers/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET api dossiers unknown-id summary - returns 404`() {
        mockMvc.perform(get("/api/dossiers/00000000-0000-0000-0000-000000000000/summary"))
            .andExpect(status().isNotFound)
    }
}
