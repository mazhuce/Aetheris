package com.aetheris.metadata.dto;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record TableCatalogGroup(String domain, String layer, long count) { }
