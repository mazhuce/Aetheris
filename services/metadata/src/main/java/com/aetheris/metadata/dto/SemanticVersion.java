package com.aetheris.metadata.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** 模型、维度和指标共用的不可变发布快照。 */
public record SemanticVersion(
        Long id,
        Integer versionNo,
        JsonNode snapshot,
        String publishedBy,
        OffsetDateTime publishedAt) { }
