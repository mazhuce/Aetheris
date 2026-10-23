package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcWriteDeadlineTest {
    private static final String SQL="INSERT INTO business.result VALUES (1)";
    private final MutableClock clock=new MutableClock();
    private final Connection connection=mock(Connection.class);
    private final Statement statement=mock(Statement.class);
    private final AtomicInteger connections=new AtomicInteger();
    private JdbcService service(Runnable duringConnect) throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        return new JdbcService(clock){@Override Connection connectWrite(DataSource ignored){connections.incrementAndGet();duringConnect.run();return connection;}};
    }
    private DataSource source(String type){var s=new DataSource();s.setType(type);s.setWriteUsername("writer");s.setWritePassword("fixture");return s;}
    @Test void expiredAdmissionDoesNotConnect() throws Exception {
        var service=service(()->{});
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,10,clock.instant().minusSeconds(1)));
        assertEquals(0,connections.get());verifyNoInteractions(statement);
    }
    @Test void connectionCannotStartSqlAfterDeadline() throws Exception {
        var service=service(()->clock.advance(11));var deadline=clock.instant().plusSeconds(10);
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,30,deadline));
        verify(connection,never()).createStatement();verify(connection).close();
    }
    @Test void statementCreationCannotConsumeTheSqlBudget() throws Exception {
        var service=service(()->{});when(connection.createStatement()).thenAnswer(call->{clock.advance(11);return statement;});
        var deadline=clock.instant().plusSeconds(10);
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,30,deadline));
        verify(statement,never()).execute(anyString());verify(statement).close();
    }
    @Test void timeoutConfigurationCannotStartLateSql() throws Exception {
        var service=service(()->{});doAnswer(call->{clock.advance(11);return null;}).when(statement).setQueryTimeout(anyInt());
        var deadline=clock.instant().plusSeconds(10);
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,30,deadline));verify(statement,never()).execute(anyString());
    }
    @Test void unsupportedQueryTimeoutNeverFallsThroughToBusinessSql() throws Exception {
        var service=service(()->{});doThrow(new SQLFeatureNotSupportedException()).when(statement).setQueryTimeout(anyInt());
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,30,clock.instant().plusSeconds(20)));
        verify(statement,never()).execute(anyString());
    }
    @Test void hiveSettingsAreAlsoBoundedBeforeTheBusinessStatement() throws Exception {
        var service=service(()->{});when(statement.execute(startsWith("SET "))).thenAnswer(call->{clock.advance(11);return false;});
        assertThrows(IllegalStateException.class,()->service.execute(source("hive2"),SQL,30,clock.instant().plusSeconds(10)));
        verify(statement,never()).execute(SQL);verify(statement,times(1)).execute(startsWith("SET "));
    }
    @Test void driverReturningAfterTheDeadlineIsNotReportedAsSuccess() throws Exception {
        var service=service(()->{});when(statement.execute(SQL)).thenAnswer(call->{clock.advance(11);return false;});
        assertThrows(IllegalStateException.class,()->service.execute(source("postgresql"),SQL,30,clock.instant().plusSeconds(10)));
        verify(statement).execute(SQL);
    }
    @Test void fullDayTimeoutAndUnknownAffectedRowsArePreserved() throws Exception {
        var service=service(()->{});when(statement.getUpdateCount()).thenReturn(-1);
        var result=service.execute(source("postgresql"),SQL,86400,clock.instant().plusSeconds(86520));
        assertEquals(true,result.get("success"));assertEquals(-1,result.get("affectedRows"));
        verify(statement).setQueryTimeout(intThat(n->n>3600&&n<=86400));verify(statement).execute(SQL);verify(connection).close();
    }
    @Test void invalidTimeoutCannotReachTheDriver() throws Exception {
        var service=service(()->{});
        for(int n:new int[]{0,-1,86401,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->service.execute(source("postgresql"),SQL,n,clock.instant().plusSeconds(100)));
        assertEquals(0,connections.get());
    }
    @Test void omittedTimeoutStillUsesTheBoundedThirtyMinuteDefault() throws Exception {
        var service=service(()->{});
        service.execute(source("postgresql"),SQL,null,clock.instant().plusSeconds(90000));
        verify(statement).setQueryTimeout(intThat(n->n>60&&n<=1800));
    }
    @Test void backwardClockCannotExtendTheSealedTimeout() throws Exception {
        var service=service(()->clock.advance(-3600));
        service.execute(source("postgresql"),SQL,30,clock.instant().plusSeconds(120));
        verify(statement).setQueryTimeout(intThat(n->n>=1&&n<=30));
    }
    private static final class MutableClock extends Clock {
        private Instant value=Instant.parse("2026-10-18T00:00:00Z");
        void advance(long seconds){value=value.plusSeconds(seconds);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return value;}
    }
}
