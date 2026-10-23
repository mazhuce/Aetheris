package com.aetheris.workflow.controller;

import com.aetheris.workflow.config.BusinessCalendar;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessCalendarControllerTest {
    @Test void browserDefaultsFollowConfiguredZoneAtMidnightAndLeapBoundary() {
        Clock instant=Clock.fixed(Instant.parse("2024-03-01T00:30:00Z"),ZoneOffset.UTC);
        var east=new BusinessCalendarController(new BusinessCalendar(instant,ZoneId.of("Asia/Shanghai"))).current();
        var west=new BusinessCalendarController(new BusinessCalendar(instant,ZoneId.of("America/Los_Angeles"))).current();
        assertEquals("2024-03-01",east.today()); assertEquals("2024-02-29",east.previousDate());
        assertEquals("2024-02-29",west.today()); assertEquals("2024-02-28",west.previousDate());
        assertEquals("America/Los_Angeles",west.zone());
    }
}
