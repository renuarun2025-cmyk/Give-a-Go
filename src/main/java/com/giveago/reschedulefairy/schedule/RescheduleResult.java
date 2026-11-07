package com.giveago.reschedulefairy.schedule;

import com.giveago.reschedulefairy.model.CalendarEvent;

import java.time.LocalDateTime;
import java.util.List;

public record RescheduleResult(List<CalendarEvent> after, List<Move> moves, List<Unplaceable> unplaceable) {

    public record Move(String uid, String title, LocalDateTime from, LocalDateTime to) {
    }

    public record Unplaceable(String uid, String title, String reason) {
    }
}
