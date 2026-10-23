package com.aetheris.metadata.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisibleListCursorTest {
    private final String hash=VisibleListCursor.hash("filters-and-user");

    @Test void canonicalRoundTripKeepsStableUpperAndConsumedPosition() {
        var first=VisibleListCursor.decode(null,50,hash,1201);
        var next=VisibleListCursor.decode(first.after(1152),50,hash,9999);
        assertEquals(1201,next.upper());
        assertEquals(1152L,next.before());
    }

    @Test void rejectsNoncanonicalOverflowChangedScopeAndSize() {
        for(String token:new String[]{"", "v1.012.1.50."+hash,"v1.12.13.50."+hash,
                "v1.12.1.050."+hash,"v1.9223372036854775808.1.50."+hash,
                "v1.12.1.50."+hash.toUpperCase(),"v1.12.1.50."+hash+"\n"})
            assertThrows(IllegalArgumentException.class,()->VisibleListCursor.decode(token,50,hash,0),token);
        String cursor=VisibleListCursor.decode(null,50,hash,12).after(1);
        assertThrows(IllegalArgumentException.class,()->VisibleListCursor.decode(cursor,51,hash,0));
        assertThrows(IllegalArgumentException.class,()->VisibleListCursor.decode(cursor,50,VisibleListCursor.hash("another-user"),0));
        assertThrows(IllegalArgumentException.class,()->VisibleListCursor.decode(null,0,hash,0));
        assertThrows(IllegalArgumentException.class,()->VisibleListCursor.decode(null,201,hash,0));
    }
}
