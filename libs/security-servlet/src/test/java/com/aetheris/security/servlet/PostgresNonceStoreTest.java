package com.aetheris.security.servlet;

import com.aetheris.security.protocol.Nonce;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PostgresNonceStoreTest {
    static final long NOW=1_800_000_000L;
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC);
    @Test void invalidInputsAreRejectedBeforeAnyDatabaseConnection() {
        AtomicInteger opened=new AtomicInteger();
        var store=new PostgresNonceStore(new TestDataSource(()->{opened.incrementAndGet();throw new SQLException();}),"metadata",CLOCK);
        for(String caller:Arrays.asList(null,"","unknown","workflow;"))
            assertThrows(IllegalArgumentException.class,()->store.claim(caller,Nonce.random(),NOW+30));
        for(String nonce:Arrays.asList(null,"","a","a".repeat(87),"AQIDBAUGBwgJCgsMDQ4PEB","x=y"))
            assertThrows(IllegalArgumentException.class,()->store.claim("workflow",nonce,NOW+30));
        for(long expiry:new long[]{NOW-1,NOW,NOW+71,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->store.claim("workflow",Nonce.random(),expiry));
        for(int limit:new int[]{-1,0,10001})assertThrows(IllegalArgumentException.class,()->store.purgeExpired(limit));
        assertEquals(0,opened.get());
    }
    @Test void schemaIsFixedAndCannotComeFromArbitraryRequestInput() {
        var source=new TestDataSource(()->{throw new SQLException();});
        for(String schema:Arrays.asList(null,"public","metadata.x","metadata;DROP SCHEMA auth","ai-agent"))
            assertThrows(IllegalArgumentException.class,()->new PostgresNonceStore(source,schema,CLOCK));
    }
    @Test void unavailableDatabaseIsNeverAnInMemorySuccess() {
        var store=new PostgresNonceStore(new TestDataSource(()->{throw new SQLException("controlled failure");}),"metadata",CLOCK);
        assertThrows(SQLException.class,()->store.claim("workflow",Nonce.random(),NOW+30));
    }
    @Test void externallyOwnedTransactionIsRejectedWithoutCommitOrInsertAndConnectionIsClosed() {
        AtomicInteger closed=new AtomicInteger(),mutations=new AtomicInteger();
        Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("getAutoCommit"))return false;
            if(method.getName().equals("close")){closed.incrementAndGet();return null;}
            mutations.incrementAndGet();throw new AssertionError("Unexpected transaction mutation");
        });
        var store=new PostgresNonceStore(new TestDataSource(()->connection),"metadata",CLOCK);
        assertThrows(SQLException.class,()->store.claim("workflow",Nonce.random(),NOW+30));
        assertEquals(1,closed.get());assertEquals(0,mutations.get());
    }
}
