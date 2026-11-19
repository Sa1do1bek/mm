package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.payload.ApiResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void upstreamAuthenticationFailureIsDistinguishableFromTransientGatewayErrors() {
        var response = handler.mmAiService(new com.enterprise.mm.service.MmAiServiceException(
            401, "UNAUTHORIZED", "Invalid service credential", "request-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().errorCode()).isEqualTo("MM_AI_UPSTREAM_UNAUTHORIZED");
    }

    @Test
    void returnsHelpfulMessageForInvalidEnumInRequestBody() {
        InvalidFormatException cause = InvalidFormatException.from(
            null,
            "Cannot deserialize value",
            "bad_status",
            EmployeeStatus.class
        );
        cause.prependPath(new EmployeeRequestStub(), "employmentStatus");

        ResponseEntity<ApiResponse<Void>> response = handler.malformedRequest(
            new HttpMessageNotReadableException("JSON parse error", cause)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
            .isEqualTo("Invalid employmentStatus 'bad_status'. Allowed values: ACTIVE, PROBATION, ON_LEAVE, SUSPENDED, DISMISSED");
        assertThat(response.getBody().errorCode()).isEqualTo("HttpMessageNotReadableException");
    }

    @Test
    void returnsHelpfulMessageForInvalidEnumInDataAccessLayer() {
        ResponseEntity<ApiResponse<Void>> response = handler.invalidQuery(
            new InvalidDataAccessApiUsageException(
                "Enum conversion failed",
                new IllegalArgumentException("No enum constant com.enterprise.mm.enums.EmployeeStatus.bad_status")
            )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
            .isEqualTo("Invalid value 'bad_status'. Allowed values: ACTIVE, PROBATION, ON_LEAVE, SUSPENDED, DISMISSED");
        assertThat(response.getBody().errorCode()).isEqualTo("InvalidDataAccessApiUsageException");
    }

    @Test
    void returnsHelpfulMessageForInvalidUuidInRequestBody() {
        InvalidFormatException cause = InvalidFormatException.from(
            null,
            "Cannot deserialize value",
            "j jkk",
            java.util.UUID.class
        );
        cause.prependPath(new EmployeeRequestStub(), "sourceDocumentId");

        ResponseEntity<ApiResponse<Void>> response = handler.malformedRequest(
            new HttpMessageNotReadableException("JSON parse error", cause)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
            .isEqualTo("Invalid sourceDocumentId 'j jkk'. Expected a UUID or omit the field");
        assertThat(response.getBody().errorCode()).isEqualTo("HttpMessageNotReadableException");
    }

    @Test
    void returnsGenericMessageForMalformedJson() {
        ResponseEntity<ApiResponse<Void>> response = handler.malformedRequest(
            new HttpMessageNotReadableException("Unexpected character at position 12")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Malformed JSON request");
        assertThat(response.getBody().errorCode()).isEqualTo("HttpMessageNotReadableException");
    }

    @Test
    void returnsBadRequestForMissingRequiredRequestHeader() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RequiredHeaderController())
            .setControllerAdvice(handler)
            .build();

        mvc.perform(get("/test/required-header").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Required request header 'X-Supplier-Portal-Token' is missing"))
            .andExpect(jsonPath("$.errorCode").value("MissingRequestHeaderException"));
    }

    @Test
    void returnsFriendlyMessageForNotNullDatabaseConstraint() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("null value in column \"company_id\" violates not-null constraint")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Required database field is missing");
        assertThat(response.getBody().errorCode()).isEqualTo("DataIntegrityViolationException");
    }

    @Test
    void returnsUsernameMessageForDuplicateUsernameConstraint() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_users_tenant_company_username\"")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Username already exists");
        assertThat(response.getBody().errorCode()).isEqualTo("DataIntegrityViolationException");
    }

    @Test
    void returnsCodeMessageForDuplicateCodeConstraint() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_companies_tenant_code\"")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Code already exists");
        assertThat(response.getBody().errorCode()).isEqualTo("DataIntegrityViolationException");
    }

    @Test
    void returnsFeatureErrorForDuplicateActiveRfqAllocation() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_rfq_pr_source_active_item\"")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Purchase request line is already allocated to an RFQ");
        assertThat(response.getBody().errorCode()).isEqualTo("rfq.aggregation.already_allocated");
    }

    @Test
    void returnsFeatureErrorForDuplicateSourceContractAward() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_contract_active_source_rfq\"")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("RFQ already has a source contract");
        assertThat(response.getBody().errorCode()).isEqualTo("rfq.award.contract_exists");
    }

    @Test
    void rfqAggregationMessageKeysResolveInSupportedBundles() {
        List<String> keys = List.of(
            "rfq.aggregation.empty_selection",
            "rfq.aggregation.duplicate_ids",
            "rfq.aggregation.ineligible_line",
            "rfq.aggregation.already_allocated",
            "rfq.aggregation.mixed_boundary",
            "rfq.aggregation.material_group_required",
            "rfq.aggregation.unit_required",
            "rfq.aggregation.cancel_after_award",
            "rfq.aggregation.active_source_soft_delete",
            "rfq.aggregation.immutable_allocated_source",
            "rfq.award.tender_not_ready",
            "rfq.award.coverage_mismatch",
            "rfq.award.contract_exists"
        );

        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("uz"))) {
            ResourceBundle bundle = ResourceBundle.getBundle("message", locale);
            assertThat(keys).allSatisfy(key -> assertThat(bundle.getString(key)).isNotBlank());
        }
    }

    @Test
    void returnsGenericMessageForUnknownDuplicateConstraint() {
        ResponseEntity<ApiResponse<Void>> response = handler.dataIntegrity(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"uk_unknown_business_key\"")
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Duplicate value violates unique constraint");
        assertThat(response.getBody().errorCode()).isEqualTo("DataIntegrityViolationException");
    }

    @Test
    void returnsGenericServerErrorForNullPointerExceptionInsteadOfMaskingAsBadRequest() {
        // A NullPointerException is always a programmer error, never something the
        // caller can fix by changing their request -- it must be a 500, must not name
        // "NullPointerException" in the client-facing errorCode, and must not leak the
        // raw (potentially field/method-revealing) exception message to the client.
        ResponseEntity<ApiResponse<Void>> response = handler.nullPointer(
            new NullPointerException(
                "Cannot invoke \"com.enterprise.mm.entity.Company.getId()\" because \"company\" is null"
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
            .isEqualTo("An unexpected server error occurred. Please try again or contact support.");
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).doesNotContain("NullPointerException");
        assertThat(response.getBody().message()).doesNotContain("Company");
        assertThat(response.getBody().errorCode()).doesNotContain("NullPointerException");
    }

    @Test
    void internalUnexpectedErrorMessageKeyResolvesInSupportedBundles() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("uz"))) {
            ResourceBundle bundle = ResourceBundle.getBundle("message", locale);
            assertThat(bundle.getString("error.internal.unexpected")).isNotBlank();
        }
    }

    @Test
    void returnsBadRequestForIllegalArgumentException() {
        ResponseEntity<ApiResponse<Void>> response = handler.illegalArgument(
            new IllegalArgumentException("Page index must not be negative"),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Page index must not be negative");
        assertThat(response.getBody().errorCode()).isEqualTo("IllegalArgumentException");
    }

    @Test
    void exposesAccreditationDecisionImmutableDomainCode() {
        var response = handler.business(
            new BusinessException(
                "Accreditation decisions are immutable",
                "ACCREDITATION_DECISION_IMMUTABLE"
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACCREDITATION_DECISION_IMMUTABLE");
    }

    @Test
    void businessExceptionWithContextSurfacesItAsResponseData() {
        java.util.Map<String, Object> context = java.util.Map.of(
            "purchaseOrderId", java.util.UUID.randomUUID(),
            "purchaseOrderCode", "PO-2026-01",
            "supplierId", java.util.UUID.randomUUID(),
            "supplierCode", "SUP-01"
        );
        var response = handler.business(
            new BusinessException(
                "Confirmed ASN is required for material purchase order receipt",
                "goodsIncome.asn.required",
                context
            ),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(context);
    }

    @Test
    void businessExceptionWithoutContextLeavesResponseDataNull() {
        var response = handler.business(
            new BusinessException("Purchase order has no items"),
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isNull();
    }

    private static final class EmployeeRequestStub {
        @SuppressWarnings("unused")
        public EmployeeStatus employmentStatus;
    }

    @RestController
    private static final class RequiredHeaderController {
        @GetMapping("/test/required-header")
        ApiResponse<String> requiredHeader(@RequestHeader("X-Supplier-Portal-Token") String token) {
            return ApiResponse.ok(token);
        }
    }
}
