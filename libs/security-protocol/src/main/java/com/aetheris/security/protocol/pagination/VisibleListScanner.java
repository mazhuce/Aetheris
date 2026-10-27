package com.aetheris.security.protocol.pagination;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared bounded scan for lists whose rows must be filtered after a database
 * keyset query.  The caller supplies the real database query and a fresh
 * authorization batch check for each candidate window.
 */
public final class VisibleListScanner {
    public static final int CANDIDATE_BATCH = 500;
    public static final int MAX_CANDIDATES_PER_REQUEST = 5000;

    private VisibleListScanner() { }

    @FunctionalInterface
    public interface Loader<T> {
        /** Return rows in descending ID order, bounded by {@code limit}. */
        List<T> load(long upper, Long before, int limit);
    }

    @FunctionalInterface
    public interface Authorizer<T> {
        /** Re-check the current trusted subject for exactly this candidate window. */
        Set<Long> allowed(List<T> candidates);
    }

    public static <T> VisibleListPage<T> page(
            String token,
            int size,
            String filterHash,
            long initialUpper,
            Loader<T> loader,
            Function<T, Long> id,
            Authorizer<T> authorizer) {
        VisibleListCursor cursor = VisibleListCursor.decode(token, size, filterHash, initialUpper);
        List<T> visible = new ArrayList<>(size);
        Long before = cursor.before();
        int scanned = 0;
        while (scanned < MAX_CANDIDATES_PER_REQUEST) {
            int windowSize = Math.min(CANDIDATE_BATCH, MAX_CANDIDATES_PER_REQUEST - scanned);
            // One look-ahead row tells us whether a cursor is still needed.
            List<T> loaded = loader.load(cursor.upper(), before, windowSize + 1);
            if (loaded == null || loaded.isEmpty()) return new VisibleListPage<>(visible, null, false);
            List<T> window = loaded.size() > windowSize ? loaded.subList(0, windowSize) : loaded;
            Set<Long> allowed = authorizer.allowed(List.copyOf(window));
            long last = 0;
            int consumedWindow = 0;
            for (T row : window) {
                Long rowId = id.apply(row);
                if (rowId == null || rowId <= 0 || rowId > cursor.upper()
                        || (before != null && rowId >= before)) {
                    throw new IllegalStateException("分页查询返回了非法的资源顺序");
                }
                last = rowId;
                consumedWindow++;
                if (allowed.contains(rowId)) {
                    visible.add(row);
                    if (visible.size() == size) break;
                }
            }
            scanned += consumedWindow;
            before = last;
            // If the page filled in the middle of a window, the unconsumed rows
            // must remain behind the cursor for the next request.
            boolean moreCandidates = consumedWindow < window.size() || loaded.size() > windowSize;
            if (visible.size() == size) {
                return moreCandidates
                        ? new VisibleListPage<>(visible, cursor.after(before), true)
                        : new VisibleListPage<>(visible, null, false);
            }
            if (!moreCandidates) return new VisibleListPage<>(visible, null, false);
            if (scanned >= MAX_CANDIDATES_PER_REQUEST) {
                return new VisibleListPage<>(visible, cursor.after(before), true);
            }
        }
        throw new IllegalStateException("分页扫描预算无效");
    }
}
