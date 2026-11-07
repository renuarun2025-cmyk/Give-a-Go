package com.giveago.reschedulefairy.ics;

public class InvalidCalendarException extends RuntimeException {

    public InvalidCalendarException(String message) {
        super(message);
    }

    public InvalidCalendarException(String message, Throwable cause) {
        super(message, cause);
    }
}
