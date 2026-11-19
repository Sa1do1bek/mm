package com.enterprise.mm.common;

import com.enterprise.mm.error.BadRequestException;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.context.i18n.LocaleContextHolder;

public final class PaginationUtils {
    public static final int MAX_PAGE_SIZE = 100;
    public static final Sort DEFAULT_CREATED_AT_DESC = Sort.by("createdAt").descending();

    private PaginationUtils() {
    }

    public static Pageable pageRequest(int page, int size) {
        return pageRequest(page, size, DEFAULT_CREATED_AT_DESC);
    }

    public static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0) {
            throw new BadRequestException("Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, localizeNameSort(sort));
    }

    public static Pageable pageRequest(int page, int size, Sort sort, String nameOwnerAlias) {
        if (page < 0) {
            throw new BadRequestException("Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, localizeNameSort(sort, nameOwnerAlias));
    }

    public static Sort sort(String sortBy, String sortDir, Set<String> allowedFields, Sort defaultSort) {
        if (sortBy == null || sortBy.isBlank()) {
            return defaultSort == null ? Sort.unsorted() : defaultSort;
        }
        String field = sortBy.trim();
        if (allowedFields == null || !allowedFields.contains(field)) {
            throw new BadRequestException("Unsupported sort field: " + sortBy);
        }
        Sort.Direction direction = direction(sortDir);
        return Sort.by(direction, field);
    }

    private static Sort.Direction direction(String sortDir) {
        if (sortDir == null || sortDir.isBlank()) {
            return Sort.Direction.DESC;
        }
        return switch (sortDir.trim().toUpperCase(Locale.ROOT)) {
            case "ASC" -> Sort.Direction.ASC;
            case "DESC" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("Unsupported sort direction: " + sortDir);
        };
    }

    public static Sort localizeNameSort(Sort sort) {
        return localizeNameSort(sort, null);
    }

    public static Sort localizeNameSort(Sort sort, String nameOwnerAlias) {
        if (sort == null || sort.isUnsorted()) {
            return Sort.unsorted();
        }
        String language = switch (LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT)) {
            case "en", "uz" -> LocaleContextHolder.getLocale().getLanguage().toLowerCase(Locale.ROOT);
            default -> LocalizedText.DEFAULT_LANGUAGE;
        };
        Sort localized = Sort.unsorted();
        for (Sort.Order order : sort) {
            Sort next = "name".equals(order.getProperty())
                ? JpaSort.unsafe(
                    order.getDirection(),
                    "multilingual_name_value(" + qualifiedName(nameOwnerAlias) + ", '" + language + "')"
                )
                : Sort.by(order);
            localized = localized.and(next);
        }
        return localized;
    }

    private static String qualifiedName(String nameOwnerAlias) {
        if (nameOwnerAlias == null || nameOwnerAlias.isBlank()) {
            return "name";
        }
        return nameOwnerAlias.trim() + ".name";
    }
}
