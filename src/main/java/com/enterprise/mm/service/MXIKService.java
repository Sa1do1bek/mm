package com.enterprise.mm.service;

import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.mxik.MXIKCreateRequest;
import com.enterprise.mm.payload.mxik.MXIKResponse;
import com.enterprise.mm.payload.mxik.MXIKUpdateRequest;
import com.enterprise.mm.repository.MXIKRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MXIKService {
    private final MXIKRepository repository;

    @Transactional(readOnly = true)
    public Slice<MXIKResponse> searchMXIK(String searchParam, String type, Pageable pageable) {
        return searchMXIK(searchParam, type, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Slice<MXIKResponse> searchMXIK(String searchParam, String type, LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        String search = searchParam == null || searchParam.isBlank() ? null : searchParam.trim();
        String searchPattern = search == null ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
        String typeFilter = type == null || type.isBlank() ? null : type.trim();
        LocalDateTime from = dateFrom == null ? null : dateFrom.atStartOfDay();
        LocalDateTime to = dateTo == null ? null : dateTo.plusDays(1).atStartOfDay();
        Slice<MXIK> slice = searchPattern == null
            ? repository.listByTypeAndCreatedAt(typeFilter, from, to, unsortedPageable(pageable))
            : repository.searchByTypeTermAndCreatedAt(searchPattern, typeFilter, from, to, unsortedPageable(pageable));
        return slice.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public MXIKResponse getMXIKById(Long id) {
        return toResponse(findEntity(id));
    }

    @Transactional
    public MXIKResponse createMXIK(MXIKCreateRequest request) {
        String kod = normalizeCode(request.getKod());
        if (repository.existsByKod(kod)) {
            throw new BadRequestException("MXIK code already exists: " + kod);
        }
        MXIK entity = new MXIK();
        apply(entity, request);
        entity.setKod(kod);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public MXIKResponse updateMXIK(Long id, MXIKUpdateRequest request) {
        MXIK entity = findEntity(id);
        String kod = normalizeCode(request.getKod());
        if (repository.existsByKodAndIdNot(kod, id)) {
            throw new BadRequestException("MXIK code already exists: " + kod);
        }
        apply(entity, request);
        entity.setKod(kod);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void deleteMXIK(Long id) {
        repository.delete(findEntity(id));
    }

    private MXIK findEntity(Long id) {
        return repository.findByIdAndTypeNotNull(id)
            .orElseThrow(() -> new NotFoundException("MXIK not found: " + id));
    }
    private MXIK findEntityCode(String code) {
        return repository.findByKodAndTypeNotNull(code)
                .orElseThrow(() -> new NotFoundException("MXIK not found: " + code));
    }

    private void apply(MXIK entity, MXIKCreateRequest request) {
        entity.setName(normalizeText(request.getName()));
        entity.setType(validateType(normalizeText(request.getType())));
    }

    private void apply(MXIK entity, MXIKUpdateRequest request) {
        entity.setName(normalizeText(request.getName()));
        entity.setType(validateType(normalizeText(request.getType())));
    }

    private String validateType(String type) {
        if (type != null && ("WORKS".equalsIgnoreCase(type.trim()) || "WORK".equalsIgnoreCase(type.trim()))) {
            throw new BadRequestException("WORKS is not a valid Material or MXIK type. Materials must be physical goods or services.");
        }
        return type;
    }


    private MXIKResponse toResponse(MXIK entity) {
        MXIKResponse out = new MXIKResponse();
        out.setId(entity.getId());
        out.setName(localizedName(entity));
        out.setNameRu(entity.getNameRu());
        out.setKod(entity.getKod());
        out.setType(entity.getType());
        out.setAttributeName(entity.getAttributeName());
        out.setAttributeNameRu(entity.getAttributeNameRu());
        out.setAttributeNameCyril(entity.getAttributeNameCyril());
        return out;
    }

    private String localizedName(MXIK entity) {
        if ("ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage())) {
            String russianName = normalizeText(entity.getNameRu());
            if (russianName != null) {
                return russianName;
            }
        }
        return entity.getName();
    }

    private String normalizeCode(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Pageable unsortedPageable(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return Pageable.unpaged();
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    public MXIKResponse getMXIKByCode(String code) {
        return toResponse(findEntityCode(code));
    }
}
