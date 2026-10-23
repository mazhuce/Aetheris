package com.aetheris.system.security;
/** Business compatibility view of v2 TrustedUser; never parsed from legacy headers. */
public record AetherisIdentity(Long uid,String username) { }
