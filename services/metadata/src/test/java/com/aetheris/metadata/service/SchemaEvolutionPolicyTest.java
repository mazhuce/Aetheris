package com.aetheris.metadata.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaEvolutionPolicyTest {
    @Test
    void classifiesSafeAndBreakingChanges() {
        assertEquals("auto_apply", SchemaEvolutionPolicy.decision("add_column"));
        assertEquals("auto_apply", SchemaEvolutionPolicy.decision("widen_type"));
        assertEquals("approval_required", SchemaEvolutionPolicy.decision("rename_column"));
        assertEquals("approval_required", SchemaEvolutionPolicy.decision("drop_column"));
        assertEquals("isolate", SchemaEvolutionPolicy.decision("narrow_type"));
    }

    @Test
    void rejectsUnknownOperation() {
        assertThrows(IllegalArgumentException.class, () -> SchemaEvolutionPolicy.decision("truncate_table"));
    }
}
