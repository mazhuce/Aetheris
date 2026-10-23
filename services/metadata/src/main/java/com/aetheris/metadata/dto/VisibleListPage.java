package com.aetheris.metadata.dto;

import java.util.List;

/** hasMore describes candidate continuation; an empty visible page may continue. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record VisibleListPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public VisibleListPage { items = List.copyOf(items); if (hasMore != (nextCursor != null)) throw new IllegalArgumentException("分页响应无效"); }
}
