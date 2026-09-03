package com.aetheris.auth.dto;

import com.aetheris.auth.model.DataScope;

import java.time.OffsetDateTime;
import java.util.List;

/** 授权列表视图：附带被授权主体名称。 */
public record GrantView(
        Long id,
        String granteeType,
        Long granteeId,
        String granteeName,
        String resourceType,
        Long resourceId,
        DataScope dataScope,
        List<String> actions,
        Boolean enabled,
        String grantedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}