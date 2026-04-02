package com.ocrsage

import com.fasterxml.jackson.databind.ObjectMapper
import com.ocrsage.controller.ExportRequest
import com.ocrsage.dto.InvoiceResponse
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * Full E2E integration tests for all API endpoints.
 * Uses a real Moroccan invoice fixture, H2 in-memory DB, and real OCR/extraction pipeline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceApiIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private var createdInvoiceId: Long = 0

    private fun loadFixture(name: String): ByteArray {
        return javaClass.classLoader.getResourceAsStream("fixtures/$name")!!.readAllBytes()
    }

    @BeforeAll
    fun setUp() {
        // Upload a real invoice that all tests can use
        val invoiceBytes = loadFixture("facture_maroc_sample.txt")
        val file = MockMultipartFile("file", "facture_maroc_sample.txt", "text/plain", invoiceBytes)

        val result = mockMvc.perform(multipart("/api/invoices").file(file))
            .andExpect(status().isCreated)
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, InvoiceResponse::class.java)
        createdInvoiceId = response.id!!
    }

    // ===== UPLOAD & PROCESS =====

    @Test
    fun `POST api invoices - upload and process extracts Moroccan invoice fields`() {
        val invoiceBytes = loadFixture("facture_maroc_sample.txt")
        val file = MockMultipartFile("file", "facture_test.txt", "text/plain", invoiceBytes)

        val result = mockMvc.perform(multipart("/api/invoices").file(file))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.fileName").value("facture_test.txt"))
            .andExpect(jsonPath("$.status").isString)
            .andExpect(jsonPath("$.currency").value("MAD"))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, InvoiceResponse::class.java)
        assertNotNull(response.id)
        assertEquals("FA-2024/0157", response.invoiceNumber)
        assertEquals("001234567000078", response.supplierIce)
        assertEquals("12345678", response.supplierIf)
        assertNotNull(response.amountHt)
        assertNotNull(response.amountTtc)
        assertNotNull(response.amountTva)

        assertTrue(
            response.status == "READY_FOR_SAGE" || response.status == "EXTRACTED",
            "Expected READY_FOR_SAGE or EXTRACTED but got ${response.status}"
        )
    }

    @Test
    fun `POST api invoices - reject empty file`() {
        val emptyFile = MockMultipartFile("file", "empty.txt", "text/plain", ByteArray(0))

        mockMvc.perform(multipart("/api/invoices").file(emptyFile))
            .andExpect(status().isBadRequest)
    }

    // ===== GET INVOICE =====

    @Test
    fun `GET api invoices id - retrieve uploaded invoice by ID`() {
        mockMvc.perform(get("/api/invoices/$createdInvoiceId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(createdInvoiceId))
            .andExpect(jsonPath("$.fileName").value("facture_maroc_sample.txt"))
            .andExpect(jsonPath("$.supplierIce").value("001234567000078"))
            .andExpect(jsonPath("$.invoiceNumber").value("FA-2024/0157"))
    }

    @Test
    fun `GET api invoices id - return 404 for non-existent invoice`() {
        mockMvc.perform(get("/api/invoices/99999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").isString)
    }

    // ===== LIST INVOICES =====

    @Test
    fun `GET api invoices - list paginated invoices`() {
        val result = mockMvc.perform(get("/api/invoices").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andReturn()

        val body = result.response.contentAsString
        // Verify the response contains invoice data (pagination format may vary)
        assertTrue(body.contains("\"id\""), "Response should contain invoice data with id field")
        assertTrue(body.contains("facture_maroc_sample.txt"), "Response should contain our uploaded invoice")
    }

    // ===== DASHBOARD =====

    @Test
    fun `GET api invoices dashboard - return stats`() {
        mockMvc.perform(get("/api/invoices/dashboard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalInvoices").isNumber)
            .andExpect(jsonPath("$.byStatus").isMap)
            .andExpect(jsonPath("$.sageSynced").isNumber)
            .andExpect(jsonPath("$.totalProcessedAmount").isNumber)
    }

    // ===== VALIDATION =====

    @Test
    fun `GET api invoices id validate - validate uploaded invoice`() {
        mockMvc.perform(get("/api/invoices/$createdInvoiceId/validate"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").isBoolean)
            .andExpect(jsonPath("$.errors").isArray)
            .andExpect(jsonPath("$.warnings").isArray)
    }

    @Test
    fun `GET api invoices id validate - return 404 for non-existent invoice`() {
        mockMvc.perform(get("/api/invoices/99999/validate"))
            .andExpect(status().isNotFound)
    }

    // ===== EXPORT CSV =====

    @Test
    fun `POST api export csv - export invoice as CSV`() {
        val request = objectMapper.writeValueAsString(ExportRequest(listOf(createdInvoiceId)))

        val result = mockMvc.perform(
            post("/api/export/csv")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("factures_export.csv")))
            .andReturn()

        val csvContent = result.response.contentAsString
        assertTrue(csvContent.contains("FA-2024/0157"), "CSV should contain the invoice number")
        assertTrue(csvContent.contains("001234567000078"), "CSV should contain the ICE")
    }

    // ===== EXPORT JSON =====

    @Test
    fun `POST api export json - export invoice as JSON`() {
        val request = objectMapper.writeValueAsString(ExportRequest(listOf(createdInvoiceId)))

        val result = mockMvc.perform(
            post("/api/export/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("factures_export.json")))
            .andReturn()

        val jsonContent = result.response.contentAsString
        assertTrue(jsonContent.contains("FA-2024/0157"), "JSON should contain the invoice number")
        assertTrue(jsonContent.contains("001234567000078"), "JSON should contain the ICE")
    }

    // ===== EXPORT UBL XML =====

    @Test
    fun `GET api export ubl id - export invoice as UBL XML`() {
        val result = mockMvc.perform(get("/api/export/ubl/$createdInvoiceId"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_XML))
            .andReturn()

        val xmlContent = result.response.contentAsString
        assertTrue(xmlContent.contains("UBLVersionID"), "UBL XML should contain UBLVersionID")
        assertTrue(xmlContent.contains("FA-2024/0157"), "UBL XML should contain the invoice number")
        assertTrue(xmlContent.contains("001234567000078"), "UBL XML should contain the ICE")
        assertTrue(xmlContent.contains("MAD"), "UBL XML should contain the currency")
    }

    @Test
    fun `GET api export ubl id - return 404 for non-existent invoice`() {
        mockMvc.perform(get("/api/export/ubl/99999"))
            .andExpect(status().isNotFound)
    }

    // ===== EXPORT EDI =====

    @Test
    fun `GET api export edi id - export invoice as EDI INVOIC`() {
        val result = mockMvc.perform(get("/api/export/edi/$createdInvoiceId"))
            .andExpect(status().isOk)
            .andReturn()

        val ediContent = result.response.contentAsString
        assertTrue(ediContent.contains("UNB"), "EDI should contain UNB segment")
        assertTrue(ediContent.contains("INVOIC"), "EDI should contain INVOIC message type")
        assertTrue(ediContent.contains("UNZ"), "EDI should contain UNZ trailer")
    }

    // ===== BATCH UPLOAD =====

    @Test
    fun `POST api invoices batch - upload multiple invoices`() {
        val invoiceBytes = loadFixture("facture_maroc_sample.txt")

        val file1 = MockMultipartFile("files", "facture1.txt", "text/plain", invoiceBytes)
        val file2 = MockMultipartFile("files", "facture2.txt", "text/plain", invoiceBytes)

        mockMvc.perform(multipart("/api/invoices/batch").file(file1).file(file2))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalFiles").value(2))
            .andExpect(jsonPath("$.successful").isNumber)
            .andExpect(jsonPath("$.results").isArray)
            .andExpect(jsonPath("$.results.length()").value(2))
    }

    @Test
    fun `POST api invoices batch - reject missing files param`() {
        // No files parameter at all - should get a 4xx error
        val result = mockMvc.perform(
            post("/api/invoices/batch")
                .contentType(MediaType.MULTIPART_FORM_DATA)
        ).andReturn()

        val status = result.response.status
        assertTrue(status in 400..499, "Expected 4xx but got $status")
    }

    // ===== BATCH QUEUE =====

    @Test
    fun `GET api invoices batch queue - return queue status`() {
        mockMvc.perform(get("/api/invoices/batch/queue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    // ===== SAGE SYNC =====

    @Test
    fun `POST api invoices id sync - sync returns 200 or 400 not 500`() {
        val result = mockMvc.perform(post("/api/invoices/$createdInvoiceId/sync"))
            .andReturn()

        val status = result.response.status
        // 200 (sync ok) or 400 (not ready) are both acceptable - no 500
        assertTrue(status == 200 || status == 400, "Expected 200 or 400 but got $status")
    }

    @Test
    fun `POST api invoices id sync - return 404 for non-existent invoice`() {
        mockMvc.perform(post("/api/invoices/99999/sync"))
            .andExpect(status().isNotFound)
    }

    // ===== ERP SETTINGS =====

    @Test
    fun `GET api settings erp - return active ERP settings`() {
        mockMvc.perform(get("/api/settings/erp"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activeType").isString)
            .andExpect(jsonPath("$.availableTypes").isArray)
            .andExpect(jsonPath("$.availableTypes.length()").value(3))
    }

    @Test
    fun `POST api settings erp - save ERP type`() {
        mockMvc.perform(
            post("/api/settings/erp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"erpType": "SAGE_1000"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `POST api settings erp test - test ERP connection`() {
        mockMvc.perform(
            post("/api/settings/erp/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"erpType": "SAGE_1000"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").isBoolean)
            .andExpect(jsonPath("$.message").isString)
    }

    // ===== EXPORT NOT FOUND =====

    @Test
    fun `POST api export csv - return 404 for non-existent invoice IDs`() {
        val request = objectMapper.writeValueAsString(ExportRequest(listOf(99999L)))

        mockMvc.perform(
            post("/api/export/csv")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            .andExpect(status().isNotFound)
    }

    // ===== HEALTH CHECK =====

    @Test
    fun `GET actuator health - application is healthy`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }
}
