package com.madaef.recondoc

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.dossier.DossierType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test@madaef.ma", roles = ["ADMIN"])
class DossierIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

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
        // Create one first
        val body = mapOf("type" to "BC", "fournisseur" to "LIST TEST")
        mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/dossiers"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET api dossiers id - get dossier detail`() {
        // Create
        val result = mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("type" to "BC", "fournisseur" to "DETAIL TEST")))
        ).andExpect(status().isCreated).andReturn()

        val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

        mockMvc.perform(get("/api/dossiers/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.documents").isArray)
            .andExpect(jsonPath("$.resultatsValidation").isArray)
    }

    @Test
    fun `PATCH api dossiers id statut - change status`() {
        val result = mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("type" to "BC")))
        ).andExpect(status().isCreated).andReturn()

        val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

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
    fun `POST api dossiers id valider - validate empty dossier`() {
        val result = mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("type" to "BC")))
        ).andExpect(status().isCreated).andReturn()

        val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

        // Validate should return empty results (no documents to verify)
        mockMvc.perform(post("/api/dossiers/$id/valider"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    fun `DELETE api dossiers id - delete dossier`() {
        val result = mockMvc.perform(
            post("/api/dossiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("type" to "BC")))
        ).andExpect(status().isCreated).andReturn()

        val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

        mockMvc.perform(delete("/api/dossiers/$id"))
            .andExpect(status().isNoContent)

        // Verify deleted
        mockMvc.perform(get("/api/dossiers/$id"))
            .andExpect(status().isNotFound)
    }
}
