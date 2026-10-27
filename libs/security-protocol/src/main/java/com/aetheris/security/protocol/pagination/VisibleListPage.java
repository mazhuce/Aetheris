package com.aetheris.security.protocol.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A bounded, authorization-aware list page.  The cursor is a position, never a permission grant. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VisibleListPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public VisibleListPage {
        items = List.copyOf(items == null ? List.of() : items);
        if (hasMore != (nextCursor != null)) throw new IllegalArgumentException("分页响应无效");
    }
}
