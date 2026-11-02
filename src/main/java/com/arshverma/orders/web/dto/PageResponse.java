package com.arshverma.orders.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable JSON shape for paged results (Spring's PageImpl serialisation is not a stable API). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
                              boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.hasNext());
    }
}
