package com.aetheris.workflow.controller;

import com.aetheris.workflow.config.BusinessCalendar;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Keep the browser's default partition date on the same clock and zone as cron. */
@RestController
public class BusinessCalendarController {
    private final BusinessCalendar calendar;
    public BusinessCalendarController(BusinessCalendar calendar) { this.calendar=calendar; }
    @GetMapping("/api/workflow/business-calendar")
    public CalendarView current() {
        var today=calendar.today();
        return new CalendarView(calendar.zone().getId(),today.toString(),today.minusDays(1).toString());
    }
    public record CalendarView(String zone,String today,String previousDate) { }
}
