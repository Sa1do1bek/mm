package com.enterprise.mm.payload;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }

    public static <T> PageResponse<T> from(List<T> content) {
        return new PageResponse<>(
            content,
            0,
            content.size(),
            content.size(),
            1
        );
    }

    public static <T> PageResponse<T> fromSlice(Slice<T> slice) {
        long seenElements = (long) slice.getNumber() * slice.getSize() + slice.getNumberOfElements();
        long totalElements = slice.hasNext() ? seenElements + 1 : seenElements;
        int totalPages = slice.getSize() == 0 ? 0 : (int) Math.ceil((double) totalElements / slice.getSize());
        return new PageResponse<>(
            slice.getContent(),
            slice.getNumber(),
            slice.getSize(),
            totalElements,
            totalPages
        );
    }
}
