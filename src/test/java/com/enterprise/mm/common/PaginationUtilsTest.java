package com.enterprise.mm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.mm.error.BadRequestException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PaginationUtilsTest {

    @Test
    void pageRequestBuildsPageableForValidValues() {
        Pageable pageable = PaginationUtils.pageRequest(1, 20, Sort.by("createdAt").descending());

        assertEquals(1, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.by("createdAt").descending(), pageable.getSort());
    }

    @Test
    void pageRequestRejectsNegativePage() {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> PaginationUtils.pageRequest(-1, 20)
        );

        assertEquals("Page index must not be negative", exception.getMessage());
    }

    @Test
    void pageRequestRejectsSizeBelowMinimum() {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> PaginationUtils.pageRequest(0, 0)
        );

        assertEquals("Size must be between 1 and 100", exception.getMessage());
    }

    @Test
    void pageRequestRejectsSizeAboveMaximum() {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> PaginationUtils.pageRequest(0, 101)
        );

        assertEquals("Size must be between 1 and 100", exception.getMessage());
    }

    @Test
    void sortBuildsAllowedSort() {
        Sort sort = PaginationUtils.sort("code", "asc", Set.of("code", "createdAt"), Sort.by("createdAt").descending());

        assertEquals(Sort.by(Sort.Direction.ASC, "code"), sort);
    }

    @Test
    void sortUsesDefaultWhenFieldMissing() {
        Sort defaultSort = Sort.by("createdAt").descending();

        Sort sort = PaginationUtils.sort(null, "asc", Set.of("code", "createdAt"), defaultSort);

        assertEquals(defaultSort, sort);
    }

    @Test
    void sortRejectsUnsupportedField() {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> PaginationUtils.sort("unknown", "asc", Set.of("code"), Sort.unsorted())
        );

        assertEquals("Unsupported sort field: unknown", exception.getMessage());
    }

    @Test
    void sortRejectsUnsupportedDirection() {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> PaginationUtils.sort("code", "sideways", Set.of("code"), Sort.unsorted())
        );

        assertEquals("Unsupported sort direction: sideways", exception.getMessage());
    }

    @Test
    void pageRequestQualifiesLocalizedNameSortWhenAliasIsProvided() {
        Pageable pageable = PaginationUtils.pageRequest(0, 20, Sort.by(Sort.Direction.ASC, "name"), "e");

        Sort.Order order = pageable.getSort().iterator().next();
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertTrue(order.getProperty().contains("multilingual_name_value(e.name,"));
    }
}
