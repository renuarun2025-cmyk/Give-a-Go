package com.giveago.reschedulefairy.ics;

import com.giveago.reschedulefairy.model.CalendarEvent;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * A parsed .ics file: the original ical4j calendar (so it can be written back unchanged apart from moves),
 * the simplified events, and the VEVENT behind each event uid.
 */
public record ParsedCalendar(Calendar calendar, List<CalendarEvent> events, Map<String, VEvent> components,
                             ZoneId zone) {
}
