package com.giveago.reschedulefairy.model;

import java.time.Duration;
import java.time.LocalDateTime;

public record CalendarEvent(String uid, String title, LocalDateTime start, LocalDateTime end, boolean movable) {

    public CalendarEvent {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Event '" + title + "' must end after it starts");
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    public CalendarEvent withStart(LocalDateTime newStart) {
        return new CalendarEvent(uid, title, newStart, newStart.plus(duration()), movable);
    }
}
