package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.enterprise.mm.entity.SupplierEvaluation;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.mapper.SupplierEvaluationMapper;
import com.enterprise.mm.payload.SupplierEvaluationCreateRequest;
import com.enterprise.mm.payload.SupplierEvaluationUpdateRequest;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SupplierEvaluationIntegrityTest {

    private final SupplierEvaluationService service = new SupplierEvaluationService(
        mock(SupplierEvaluationRepository.class),
        new SupplierEvaluationMapper(),
        mock(SupplierRepository.class),
        mock(PurchaseOrderRepository.class),
        mock(AuditService.class),
        new ObjectMapper(),
        mock(SupplierAdmissionService.class)
    );

    @Test
    void genericCreateIgnoresClientTotalAndCalculatesWeightedScore() {
        SupplierEvaluation entity = scoredEvaluation("100", "80", "60", "40", "20");
        entity.setTotalScore(new BigDecimal("999"));

        service.beforeCreate(entity, new SupplierEvaluationCreateRequest());

        assertThat(entity.getTotalScore()).isEqualByComparingTo("76.00");
    }

    @Test
    void genericUpdateRecalculatesWeightedScoreAfterEditableComponentsChange() {
        SupplierEvaluation entity = scoredEvaluation("50", "50", "50", "50", "50");
        entity.setTotalScore(BigDecimal.ZERO);

        service.beforeUpdate(entity, new SupplierEvaluationUpdateRequest());

        assertThat(entity.getTotalScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void genericMutationRejectsScoresOutsideZeroToOneHundred() {
        SupplierEvaluation entity = scoredEvaluation("101", "80", "60", "40", "20");

        assertThatThrownBy(() -> service.beforeCreate(entity, new SupplierEvaluationCreateRequest()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("between 0 and 100");
    }

    @Test
    void genericUpdateCannotRewriteEvaluationIdentityOrAuthoritativeResult() {
        SupplierEvaluation entity = scoredEvaluation("80", "80", "80", "80", "80");
        entity.setCode("EVAL-001");
        entity.setStatus("POSTED");
        entity.setEvaluationSource("GOODS_RECEIPT");
        entity.setTotalScore(new BigDecimal("80"));
        SupplierEvaluationUpdateRequest request = new SupplierEvaluationUpdateRequest();
        request.setCode("ATTACKER-CODE");
        request.setStatus("DRAFT");
        request.setEvaluationSource("MANUAL");
        request.setTotalScore(new BigDecimal("999"));
        request.setQualityScore(new BigDecimal("90"));

        new SupplierEvaluationMapper().updateEntity(entity, request);

        assertThat(entity.getCode()).isEqualTo("EVAL-001");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getEvaluationSource()).isEqualTo("GOODS_RECEIPT");
        assertThat(entity.getTotalScore()).isEqualByComparingTo("80");
        assertThat(entity.getQualityScore()).isEqualByComparingTo("90");
        assertThat(entity.getDeliveryScore()).isEqualByComparingTo("80");
    }

    private SupplierEvaluation scoredEvaluation(
        String delivery,
        String quality,
        String quantity,
        String price,
        String claims
    ) {
        SupplierEvaluation entity = new SupplierEvaluation();
        entity.setDeliveryScore(new BigDecimal(delivery));
        entity.setQualityScore(new BigDecimal(quality));
        entity.setQuantityScore(new BigDecimal(quantity));
        entity.setPriceScore(new BigDecimal(price));
        entity.setClaimsScore(new BigDecimal(claims));
        return entity;
    }
}
