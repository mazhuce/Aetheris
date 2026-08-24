package com.aetheris.metadata.dto;

public record DataSourceColumnDto(
        String name,
        String type,
        Integer size,
        Boolean nullable,
        Integer ordinal,
        String remarks) {
}
