package com.aetheris.serving.model;

public record IssuedApiKey(ApiKeySummary summary, String secret) { }
