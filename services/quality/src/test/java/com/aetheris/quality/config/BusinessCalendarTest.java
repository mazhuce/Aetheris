package com.aetheris.quality.config;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class BusinessCalendarTest {
    @Test void explicitZoneControlsBothManualAndScheduledPartitionDates() {
        Clock utc=Clock.fixed(Instant.parse("2026-10-18T17:00:00Z"),ZoneOffset.UTC);
        var shanghai=new BusinessCalendar(utc,ZoneId.of("Asia/Shanghai"));
        assertEquals(LocalDate.of(2026,10,19),shanghai.today());
        assertEquals("2026-10-18",shanghai.resolve(null));
        assertEquals("2026-10-17",shanghai.previousDate(Instant.parse("2026-10-18T15:59:59Z")));
        assertEquals("2026-10-18",shanghai.previousDate(Instant.parse("2026-10-18T16:00:00Z")));
        assertEquals(LocalDate.of(2026,10,18),new BusinessCalendar(utc,ZoneOffset.UTC).today());
    }
    @Test void rejectsImpossibleDatesAndAcceptsRealLeapDay() {
        assertEquals(LocalDate.of(2024,2,29),BusinessCalendar.parseExact("2024-02-29"));
        for(String value:java.util.Arrays.asList("2026-02-29","2026-02-30","2026-4-01","0000-01-01","2026-04-31",null))
            assertThrows(IllegalArgumentException.class,()->BusinessCalendar.parseExact(value));
    }
}