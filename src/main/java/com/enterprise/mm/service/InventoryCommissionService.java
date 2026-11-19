package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.entity.InventoryCommissionMemberSetting;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.InventoryCommissionMemberResponse;
import com.enterprise.mm.payload.InventoryCommissionMemberSettingRequest;
import com.enterprise.mm.payload.InventoryCommissionSettingsRequest;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.InventoryCommissionMemberSettingRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryCommissionService {
    private final CommissionService commissionService;
    private final CommissionMemberRepository memberRepository;
    private final InventoryCommissionMemberSettingRepository settingRepository;

    @Transactional(readOnly = true)
    public List<InventoryCommissionMemberResponse> get(UUID commissionId) {
        requireInventoryCommission(commissionId);
        Map<UUID, Boolean> visibility = visibilityByMember(commissionId);
        return activeMembers(commissionId).stream()
            .map(member -> toResponse(member, visibility.getOrDefault(member.getId(), defaultVisibility(member))))
            .toList();
    }

    @Transactional
    public List<InventoryCommissionMemberResponse> update(UUID commissionId, InventoryCommissionSettingsRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> java.util.Set.of("ROLE_ADMIN", "ROLE_MM_MANAGER").contains(a.getAuthority())))
            throw new org.springframework.security.access.AccessDeniedException("Inventory visibility settings require MM manager authority");
        Commission commission = requireInventoryCommission(commissionId);
        List<CommissionMember> members = activeMembers(commissionId);
        Map<UUID, CommissionMember> membersById = new LinkedHashMap<>();
        members.forEach(member -> membersById.put(member.getId(), member));

        Set<UUID> requestedIds = new LinkedHashSet<>();
        for (InventoryCommissionMemberSettingRequest item : request.members()) {
            if (!requestedIds.add(item.commissionMemberId())) {
                throw new BadRequestException("Inventory commission member setting is duplicated: " + item.commissionMemberId());
            }
            if (!membersById.containsKey(item.commissionMemberId())) {
                throw new BadRequestException("Commission member does not belong to the inventory commission: " + item.commissionMemberId());
            }
        }
        if (!requestedIds.equals(membersById.keySet())) {
            throw new BadRequestException("Settings must be provided for every active inventory commission member");
        }

        Map<UUID, InventoryCommissionMemberSetting> existing = new LinkedHashMap<>();
        settingRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), commissionId
        ).forEach(setting -> existing.put(setting.getCommissionMemberId(), setting));

        List<InventoryCommissionMemberSetting> settings = request.members().stream().map(item -> {
            InventoryCommissionMemberSetting setting = existing.remove(item.commissionMemberId());
            if (setting == null) {
                return createSetting(commission, membersById.get(item.commissionMemberId()), item.showSystemQuantityToCounter());
            }
            setting.setShowSystemQuantityToCounter(item.showSystemQuantityToCounter());
            return setting;
        }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        existing.values().forEach(InventoryCommissionMemberSetting::softDelete);
        settings.addAll(existing.values());
        settingRepository.saveAll(settings);
        return get(commissionId);
    }

    public boolean canSeeSystemQuantity(UUID commissionId, UUID commissionMemberId) {
        return settingRepository.findFirstByTenantIdAndCommissionIdAndCommissionMemberIdAndIsDeletedFalse(
                TenantContext.currentTenant(), commissionId, commissionMemberId
            )
            .map(InventoryCommissionMemberSetting::isShowSystemQuantityToCounter)
            .orElse(false);
    }

    private boolean defaultVisibility(CommissionMember member) {
        return java.util.Set.of(com.enterprise.mm.enums.CommissionMemberRole.CHAIRMAN, com.enterprise.mm.enums.CommissionMemberRole.CHECKER, com.enterprise.mm.enums.CommissionMemberRole.ACCOUNTANT).contains(member.getMemberRole());
    }

    private Commission requireInventoryCommission(UUID commissionId) {
        Commission commission = commissionService.findEntity(commissionId);
        if (commission.getType() != CommissionType.INVENTORY) {
            throw new BadRequestException("Only INVENTORY commission can have inventory settings");
        }
        if (!commission.isActive()) {
            throw new BadRequestException("Commission is not active: " + commissionId);
        }
        return commission;
    }

    private List<CommissionMember> activeMembers(UUID commissionId) {
        return memberRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), commissionId
            ).stream()
            .filter(CommissionMember::isActive)
            .toList();
    }

    private Map<UUID, Boolean> visibilityByMember(UUID commissionId) {
        Map<UUID, Boolean> result = new LinkedHashMap<>();
        settingRepository.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), commissionId
        ).forEach(setting -> result.put(setting.getCommissionMemberId(), setting.isShowSystemQuantityToCounter()));
        return result;
    }

    private InventoryCommissionMemberSetting createSetting(Commission commission, CommissionMember member, boolean visible) {
        InventoryCommissionMemberSetting setting = new InventoryCommissionMemberSetting();
        setting.setCompanyId(commission.getCompanyId());
        setting.setCommissionId(commission.getId());
        setting.setCommissionMemberId(member.getId());
        setting.setShowSystemQuantityToCounter(visible);
        setting.setCode("INV-COM-SET-" + UUID.randomUUID().toString().substring(0, 8));
        setting.setName(member.getMemberName());
        setting.setStatus("ACTIVE");
        return setting;
    }

    private InventoryCommissionMemberResponse toResponse(CommissionMember member, boolean visible) {
        InventoryCommissionMemberResponse response = new InventoryCommissionMemberResponse();
        response.setId(member.getId());
        response.setUserId(member.getUserId());
        response.setEmployeeId(member.getEmployeeId());
        response.setName(member.getName());
        response.setMemberName(member.getMemberName());
        response.setRole(member.getMemberRole());
        response.setActive(member.isActive());
        response.setShowSystemQuantityToCounter(visible);
        return response;
    }
}
