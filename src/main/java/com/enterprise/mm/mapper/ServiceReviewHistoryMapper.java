package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ServiceReviewHistory;
import com.enterprise.mm.payload.ServiceReviewHistoryResponse;
import org.springframework.stereotype.Component;

@Component
public class ServiceReviewHistoryMapper {
    public ServiceReviewHistoryResponse toResponse(ServiceReviewHistory entity) {
        if (entity == null) {
            return null;
        }
        ServiceReviewHistoryResponse dto = new ServiceReviewHistoryResponse();
        BaseMapper.copyBase(entity, dto);
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceActId(entity.getServiceActId());
        dto.setServiceClaimId(entity.getServiceClaimId());
        dto.setClaimOriginType(entity.getClaimOriginType());
        dto.setClaimOriginId(entity.getClaimOriginId());
        dto.setReviewType(entity.getReviewType());
        dto.setDecision(entity.getDecision());
        dto.setActor(entity.getActor());
        dto.setActedAt(entity.getActedAt());
        dto.setCommentText(entity.getCommentText());
        return dto;
    }
}
