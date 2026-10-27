package com.aetheris.security.protocol.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VisibleListScannerTest {
    private static final String HASH = VisibleListCursor.hash("quality|alice|1");

    @Test
    void scansPastHiddenRowsWithoutLimitBeforeAuthorization() {
        var page = VisibleListScanner.page(null, 2, HASH, 6,
                (upper, before, limit) -> {
                    List<Long> all = before == null ? List.of(6L, 5L, 4L, 3L, 2L, 1L)
                            : List.of(2L, 1L);
                    return all.stream().filter(id -> id <= upper && (before == null || id < before))
                            .limit(limit).map(TestRow::new).toList();
                }, TestRow::id, rows -> rows.stream().map(TestRow::id).filter(id -> id == 2L || id == 1L).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(2L, 1L), page.items().stream().map(TestRow::id).toList());
        assertFalse(page.hasMore());
    }

    @Test
    void cursorBindsFilterAndFreshAuthorizationIsCalledPerWindow() {
        var calls = new int[1];
        var first = VisibleListScanner.page(null, 1, HASH, 3,
                (upper, before, limit) -> before == null ? List.of(new TestRow(3L), new TestRow(2L)) : List.of(new TestRow(1L)),
                TestRow::id, rows -> { calls[0]++; return Set.of(3L); });
        assertTrue(first.hasMore());
        assertThrows(IllegalArgumentException.class, () -> VisibleListCursor.decode(first.nextCursor(), 2, HASH, 0));
        assertEquals(1, calls[0]);
    }

    private record TestRow(long id) { }
}
