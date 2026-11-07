package com.giveago.reschedulefairy.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TimeWindow(LocalDate date, LocalTime start, LocalTime end) {

    public TimeWindow {
        if (date == null || start == null || end == null) {
            throw new IllegalArgumentException("Target window needs a date, start and end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Target window on " + date + " must end after it starts");
        }
    }

    public LocalDateTime startDateTime() {
        return date.atTime(start);
    }

    public LocalDateTime endDateTime() {
        return date.atTime(end);
    }
}
