package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceExecutionUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ServiceExecutionMapper {
    public ServiceExecutionResponse toResponse(ServiceExecution entity) {
        if (entity == null) {
            return null;
        }
        ServiceExecutionResponse dto = new ServiceExecutionResponse();
        BaseMapper.copyBase(entity, dto);
        dto.setServiceRequestId(entity.getServiceRequestId());
        dto.setServiceRequestCode(BaseMapper.code(entity.getServiceRequest()));
        dto.setServiceRequestName(BaseMapper.name(entity.getServiceRequest()));
        dto.setServiceOrderId(entity.getServiceOrderId());
        dto.setServiceOrderCode(BaseMapper.code(entity.getServiceOrder()));
        dto.setServiceOrderName(BaseMapper.name(entity.getServiceOrder()));
        dto.setPlannedStartDate(entity.getPlannedStartDate());
        dto.setPlannedEndDate(entity.getPlannedEndDate());
        dto.setActualStartDate(entity.getActualStartDate());
        dto.setActualEndDate(entity.getActualEndDate());
        dto.setProgressPercent(entity.getProgressPercent());
        dto.setResponsibleUserId(entity.getResponsibleUserId());
        dto.setResponsibleUserCode(BaseMapper.userCode(entity.getResponsibleUser()));
        dto.setResponsibleUserName(BaseMapper.userName(entity.getResponsibleUser()));
        dto.setRemarks(entity.getRemarks());
        dto.setExecutionReport(entity.getExecutionReport());
        return dto;
    }

    public ServiceExecution toEntity(ServiceExecutionCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ServiceExecution entity = new ServiceExecution();
        update(entity, dto);
        return entity;
    }

    public void updateEntity(ServiceExecution entity, ServiceExecutionUpdateRequest dto) {
        update(entity, dto);
    }

    private void update(ServiceExecution entity, ServiceExecutionCreateRequest dto) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setServiceRequestId(dto.getServiceRequestId());
        entity.setServiceOrderId(dto.getServiceOrderId());
        entity.setPlannedStartDate(dto.getPlannedStartDate());
        entity.setPlannedEndDate(dto.getPlannedEndDate());
        entity.setActualStartDate(dto.getActualStartDate());
        entity.setActualEndDate(dto.getActualEndDate());
        entity.setProgressPercent(dto.getProgressPercent());
        entity.setResponsibleUserId(dto.getResponsibleUserId());
        entity.setRemarks(dto.getRemarks());
        entity.setExecutionReport(dto.getExecutionReport());
    }
}
