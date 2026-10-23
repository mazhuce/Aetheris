package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DataSourceWriteCredentialsTest {
    static DataSource source(){var ds=new DataSource();ds.setType("postgresql");ds.setHost("127.0.0.1");ds.setPort(5432);ds.setDatabaseName("isolated");ds.setUsername("reader");ds.setPassword("read-fixture");ds.setStatus("active");return ds;}
    @Test void writeWithoutIndependentCredentialsFailsBeforeAnyDriverCall() throws Exception {
        try(var driver=mockStatic(DriverManager.class)){
            var connection=mock(Connection.class);var statement=mock(Statement.class);when(connection.createStatement()).thenReturn(statement);
            driver.when(()->DriverManager.getConnection(anyString(),anyString(),anyString())).thenReturn(connection);
            assertThrows(IllegalArgumentException.class,()->new JdbcService().execute(source(),"INSERT INTO target_table(id) VALUES (1)",5));
            driver.verifyNoInteractions();
        }
    }
    @Test void independentWriterAndReaderReachDriverWithOnlyTheirOwnDecryptedSecrets() throws Exception {
        var secrets=new DataSourceSecretService(Base64.getEncoder().encodeToString(new byte[32]));
        var ds=source();ds.setPassword(secrets.encrypt("read-fixture"));ds.setWriteUsername("writer");ds.setWritePassword(secrets.encrypt("write-fixture"));
        var jdbc=new JdbcService(secrets,false,new OutboundTargetPolicy());
        try(var driver=mockStatic(DriverManager.class)){
            driver.when(()->DriverManager.getConnection(anyString(),anyString(),anyString())).thenReturn(mock(Connection.class));
            jdbc.connect(ds).close();jdbc.connectWrite(ds).close();
            driver.verify(()->DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/isolated","reader","read-fixture"));
            driver.verify(()->DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/isolated","writer","write-fixture"));
        }
    }
    @Test void incompleteSameAccountOrCredentialOverridesFailBeforeAnyDriver() throws Exception {
        try(var driver=mockStatic(DriverManager.class)){
            for(String mode:List.of("missing","password","same","case","urlUser","parameterPassword","urlUserInfo","role")){
                var ds=source();ds.setWriteUsername("writer");ds.setWritePassword("write-fixture");
                switch(mode){case "missing"->ds.setWriteUsername(null);case "password"->ds.setWritePassword(null);case "same"->ds.setWriteUsername("reader");case "case"->ds.setWriteUsername(" READER ");
                    case "urlUser"->ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/isolated?user=other");case "parameterPassword"->ds.setConnectionParams("password=other");
                    case "urlUserInfo"->ds.setJdbcUrl("jdbc:postgresql://other:secret@127.0.0.1:5432/isolated");case "role"->ds.setConnectionParams("options=-c role=other");}
                assertThrows(IllegalArgumentException.class,()->new JdbcService().execute(ds,"INSERT INTO target_table(id) VALUES(1)",5),mode);
            }
            assertThrows(IllegalArgumentException.class,()->new JdbcService().transfer(source(),source(),"SELECT id FROM source_table","target_table","replace",1));
            driver.verifyNoInteractions();
        }
    }
    @Test void secretsAreWriteOnlyAndOnlyWriterConfiguredIsReturned() throws Exception {
        var ds=source();ds.setWriteUsername("writer");ds.setWritePassword("write-private");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String json=mapper.writeValueAsString(ds);
        assertFalse(json.contains("read-fixture"));assertFalse(json.contains("write-private"));assertFalse(json.contains("writeUsername"));assertFalse(json.contains("writePassword"));assertTrue(mapper.readTree(json).get("writeConfigured").asBoolean());
        var decoded=mapper.readValue("{\"username\":\"reader\",\"password\":\"read\",\"writeUsername\":\"writer\",\"writePassword\":\"write\",\"disableWriteCredentials\":true}",DataSource.class);
        assertEquals("write",decoded.getWritePassword());assertTrue(decoded.getDisableWriteCredentials());
        assertNull(mapper.readValue("{\"connectionRevision\":99}",DataSource.class).getConnectionRevision(),"Clients cannot forge the internal snapshot fence");
    }
}
