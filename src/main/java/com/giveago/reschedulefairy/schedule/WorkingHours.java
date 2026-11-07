package com.giveago.reschedulefairy.schedule;

import java.time.LocalTime;

public record WorkingHours(LocalTime start, LocalTime end) {

    public static final WorkingHours DEFAULT = new WorkingHours(LocalTime.of(9, 0), LocalTime.of(17, 0));

    public WorkingHours {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Working hours must end after they start");
        }
    }
}
