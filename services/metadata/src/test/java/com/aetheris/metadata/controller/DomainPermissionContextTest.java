package com.aetheris.metadata.controller;
import com.aetheris.metadata.entity.BusinessDomain;
import com.aetheris.metadata.service.BusinessDomainService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DomainPermissionContextTest {
    @Test void idAndCodeComeFromTheSamePersistedDomain() {
        var domains=mock(BusinessDomainService.class);
        var domain=new BusinessDomain();domain.setId(5L);domain.setDomainCode("SALES");
        when(domains.get(5L)).thenReturn(domain);
        assertEquals(Map.of("domainId","5","domainCode","SALES"),new DomainPermissionContext(domains).of(5L));
        assertEquals(Map.of(),new DomainPermissionContext(domains).of(99L));
        domain.setId(9L);
        assertEquals(Map.of(),new DomainPermissionContext(domains).of(5L));
    }
}
