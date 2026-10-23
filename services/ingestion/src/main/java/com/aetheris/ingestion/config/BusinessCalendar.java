package com.aetheris.ingestion.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** One explicit business calendar; machine default timezone never determines partition dates. */
@Component
public class BusinessCalendar {
    private final Clock clock;
    private final ZoneId zone;
    public BusinessCalendar() { this(Clock.systemUTC(), ZoneId.of("Asia/Shanghai")); }
    public BusinessCalendar(Clock clock, ZoneId zone) { this.clock = clock; this.zone = zone; }
    @Autowired
    public BusinessCalendar(@Value("${aetheris.business-zone:Asia/Shanghai}") String zone,
                            ObjectProvider<Clock> clocks) {
        this(clocks.getIfAvailable(Clock::systemUTC), ZoneId.of(zone));
    }
    public ZoneId zone() { return zone; }
    public LocalDate today() { return LocalDate.now(clock.withZone(zone)); }
    public String previousDate(Instant scheduledFireTime) {
        return (scheduledFireTime == null ? today() : scheduledFireTime.atZone(zone).toLocalDate())
                .minusDays(1).toString();
    }
    public String resolve(String value) {
        return value == null || value.isBlank() ? today().minusDays(1).toString() : parseExact(value).toString();
    }
    public static LocalDate parseExact(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("业务日期必须为有效的 yyyy-MM-dd 日期");
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1 || !date.toString().equals(value)) throw new IllegalArgumentException();
            return date;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("业务日期必须为有效的 yyyy-MM-dd 日期");
        }
    }
}