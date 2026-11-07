package com.giveago.reschedulefairy.ics;

import com.giveago.reschedulefairy.model.CalendarEvent;
import com.giveago.reschedulefairy.schedule.RescheduleResult;
import com.giveago.reschedulefairy.schedule.RescheduleService;
import com.giveago.reschedulefairy.schedule.TimeWindow;
import com.giveago.reschedulefairy.schedule.WorkingHours;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsRoundTripTest {

    private static final LocalDate THU = LocalDate.of(2026, 11, 5);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 6);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 11, 9);

    private final IcsCalendarService ics = new IcsCalendarService();
    private final RescheduleService rescheduler = new RescheduleService();

    static byte[] sampleIcs() throws IOException {
        try (InputStream in = IcsRoundTripTest.class.getResourceAsStream("/static/sample.ics")) {
            assertThat(in).as("sample.ics on classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private static Map<String, CalendarEvent> byTitle(List<CalendarEvent> events) {
        return events.stream().collect(Collectors.toMap(CalendarEvent::title, Function.identity()));
    }

    @Test
    void parsesSampleAndClassifiesFixedEvents() throws IOException {
        Map<String, CalendarEvent> events = byTitle(ics.parse(sampleIcs()).events());

        assertThat(events).hasSize(14);
        assertThat(events.get("Client kickoff - Northwind").movable()).isFalse();
        assertThat(events.get("[fixed] Board prep").movable()).isFalse();
        assertThat(events.get("Vendor sync - Globex").movable()).isFalse();
        assertThat(events.get("Roadmap deep-dive").movable()).isTrue();
        assertThat(events.get("1:1 with Sam").movable()).isTrue();
        assertThat(events.get("Design review").movable()).isTrue();
        assertThat(events.values().stream().filter(e -> e.start().toLocalDate().equals(THU)))
                .hasSize(5)
                .filteredOn(CalendarEvent::movable).hasSize(3);
    }

    @Test
    void rescheduleWriteAndReparseKeepsMovedTimes() throws IOException {
        ParsedCalendar parsed = ics.parse(sampleIcs());
        RescheduleResult result = rescheduler.reschedule(parsed.events(), THU,
                List.of(new TimeWindow(FRI, LocalTime.of(13, 0), LocalTime.of(17, 0)),
                        new TimeWindow(NEXT_MON, LocalTime.of(13, 0), LocalTime.of(17, 0))),
                WorkingHours.DEFAULT, 15);

        assertThat(result.moves()).hasSize(2);
        assertThat(result.unplaceable()).extracting(RescheduleResult.Unplaceable::title)
                .containsExactly("Roadmap deep-dive");

        byte[] written = ics.write(parsed, result.moves());
        Map<String, CalendarEvent> reparsed = byTitle(ics.parse(written).events());

        assertThat(reparsed).hasSize(14);
        assertThat(reparsed.get("1:1 with Sam").start()).isEqualTo(LocalDateTime.of(2026, 11, 6, 13, 0));
        assertThat(reparsed.get("1:1 with Sam").end()).isEqualTo(LocalDateTime.of(2026, 11, 6, 13, 30));
        assertThat(reparsed.get("Design review").start()).isEqualTo(LocalDateTime.of(2026, 11, 6, 15, 15));
        assertThat(reparsed.get("Design review").end()).isEqualTo(LocalDateTime.of(2026, 11, 6, 16, 15));
        assertThat(reparsed.get("Roadmap deep-dive").start()).isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 15));
        assertThat(reparsed.get("[fixed] Board prep").start()).isEqualTo(LocalDateTime.of(2026, 11, 5, 14, 0));
        assertThat(new String(written, StandardCharsets.UTF_8)).contains("UID:sample-07@reschedule-fairy");
    }

    @Test
    void movedEventsGetBumpedSequenceAndDtStampSoCalendarAppsUpdateThem() {
        String calendar = String.join("\r\n",
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
                "BEGIN:VEVENT", "UID:moved@test", "DTSTAMP:20261101T120000Z", "SEQUENCE:2",
                "DTSTART:20261105T100000", "DTEND:20261105T110000", "SUMMARY:Moved", "END:VEVENT",
                "BEGIN:VEVENT", "UID:new@test", "DTSTAMP:20261101T120000Z",
                "DTSTART:20261105T120000", "DTEND:20261105T130000", "SUMMARY:No sequence", "END:VEVENT",
                "BEGIN:VEVENT", "UID:stays@test", "DTSTAMP:20261101T120000Z",
                "DTSTART:20261105T140000", "DTEND:20261105T150000", "SUMMARY:Stays", "END:VEVENT",
                "END:VCALENDAR", "");
        ParsedCalendar parsed = ics.parse(calendar.getBytes(StandardCharsets.UTF_8));
        byte[] written = ics.write(parsed, List.of(
                new RescheduleResult.Move("moved@test", "Moved",
                        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDateTime.of(2026, 11, 6, 13, 0)),
                new RescheduleResult.Move("new@test", "No sequence",
                        LocalDateTime.of(2026, 11, 5, 12, 0), LocalDateTime.of(2026, 11, 6, 15, 0))));

        String[] blocks = new String(written, StandardCharsets.UTF_8).replace("\r\n", "\n").split("BEGIN:VEVENT");
        String moved = blocks[1], noSequence = blocks[2], stays = blocks[3];
        assertThat(moved).contains("SEQUENCE:3").contains("LAST-MODIFIED:").doesNotContain("DTSTAMP:20261101T120000Z");
        assertThat(noSequence).contains("SEQUENCE:1").doesNotContain("DTSTAMP:20261101T120000Z");
        assertThat(stays).contains("DTSTAMP:20261101T120000Z").doesNotContain("SEQUENCE").doesNotContain("LAST-MODIFIED");
    }

    @Test
    void preservesTimeZonesWhenWritingMovedEvents() {
        String calendar = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//test//EN
                BEGIN:VEVENT
                UID:tz-1
                DTSTAMP:20261101T120000Z
                DTSTART;TZID=Europe/Dublin:20261105T100000
                DTEND;TZID=Europe/Dublin:20261105T110000
                SUMMARY:Zoned
                END:VEVENT
                BEGIN:VEVENT
                UID:utc-1
                DTSTAMP:20261101T120000Z
                DTSTART:20261105T120000Z
                DTEND:20261105T123000Z
                SUMMARY:Utc
                END:VEVENT
                END:VCALENDAR
                """.replace("\n", "\r\n");
        ParsedCalendar parsed = ics.parse(calendar.getBytes(StandardCharsets.UTF_8));
        RescheduleResult result = rescheduler.reschedule(parsed.events(), THU,
                List.of(new TimeWindow(FRI, LocalTime.of(9, 0), LocalTime.of(17, 0))), WorkingHours.DEFAULT, 0);

        String written = new String(ics.write(parsed, result.moves()), StandardCharsets.UTF_8);
        assertThat(written).contains("DTSTART;TZID=Europe/Dublin:20261106T090000")
                .contains("DTEND;TZID=Europe/Dublin:20261106T100000")
                .contains("DTSTART:20261106T100000Z")
                .contains("DTEND:20261106T103000Z");
    }

    @Test
    void generatedUidsDoNotCollideWithExistingOnes() {
        String calendar = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//test//EN
                BEGIN:VEVENT
                DTSTAMP:20261101T120000Z
                DTSTART:20261105T100000
                DTEND:20261105T110000
                SUMMARY:No uid
                END:VEVENT
                BEGIN:VEVENT
                UID:reschedule-fairy-1
                DTSTAMP:20261101T120000Z
                DTSTART:20261105T120000
                DTEND:20261105T130000
                SUMMARY:Has uid
                END:VEVENT
                END:VCALENDAR
                """.replace("\n", "\r\n");
        ParsedCalendar parsed = ics.parse(calendar.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.events()).extracting(CalendarEvent::uid).doesNotHaveDuplicates().contains("reschedule-fairy-1");

        Map<String, CalendarEvent> reparsed = byTitle(ics.parse(ics.write(parsed, List.of())).events());
        assertThat(reparsed.get("No uid").uid()).isEqualTo(byTitle(parsed.events()).get("No uid").uid());
    }

    @Test
    void recurringOccurrencesBlockSlotsAndExceptionsAreFixed() {
        String calendar = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//test//EN
                BEGIN:VEVENT
                UID:daily-sync
                DTSTAMP:20261101T120000Z
                DTSTART:20261102T130000
                DTEND:20261102T140000
                RRULE:FREQ=DAILY;COUNT=10
                SUMMARY:Daily sync
                END:VEVENT
                BEGIN:VEVENT
                UID:daily-sync
                RECURRENCE-ID:20261109T130000
                DTSTAMP:20261101T120000Z
                DTSTART:20261109T150000
                DTEND:20261109T160000
                SUMMARY:Daily sync (moved)
                END:VEVENT
                BEGIN:VEVENT
                UID:planning
                DTSTAMP:20261101T120000Z
                DTSTART:20261105T100000
                DTEND:20261105T110000
                SUMMARY:Planning
                END:VEVENT
                END:VCALENDAR
                """.replace("\n", "\r\n");
        ParsedCalendar parsed = ics.parse(calendar.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.events()).filteredOn(e -> e.title().startsWith("Daily sync"))
                .hasSize(10)
                .allSatisfy(e -> assertThat(e.movable()).isFalse());
        assertThat(parsed.events()).extracting(CalendarEvent::start)
                .doesNotContain(LocalDateTime.of(2026, 11, 9, 13, 0))
                .contains(LocalDateTime.of(2026, 11, 9, 15, 0), LocalDateTime.of(2026, 11, 6, 13, 0));

        RescheduleResult friday = rescheduler.reschedule(parsed.events(), THU,
                List.of(new TimeWindow(FRI, LocalTime.of(13, 0), LocalTime.of(17, 0))), WorkingHours.DEFAULT, 0);
        assertThat(friday.moves()).singleElement().satisfies(m -> assertThat(m.to()).isEqualTo(FRI.atTime(14, 0)));

        RescheduleResult monday = rescheduler.reschedule(parsed.events(), THU,
                List.of(new TimeWindow(NEXT_MON, LocalTime.of(13, 0), LocalTime.of(17, 0))), WorkingHours.DEFAULT, 0);
        assertThat(monday.moves()).singleElement().satisfies(m -> assertThat(m.to()).isEqualTo(NEXT_MON.atTime(13, 0)));
    }

    @Test
    void allDayRecurrenceExceptionsAreIgnored() {
        String calendar = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//test//EN
                BEGIN:VEVENT
                UID:offsite
                DTSTAMP:20261101T120000Z
                DTSTART;VALUE=DATE:20261102
                RRULE:FREQ=WEEKLY;COUNT=4
                SUMMARY:Offsite
                END:VEVENT
                BEGIN:VEVENT
                UID:offsite
                RECURRENCE-ID;VALUE=DATE:20261109
                DTSTAMP:20261101T120000Z
                DTSTART;VALUE=DATE:20261110
                SUMMARY:Offsite (moved)
                END:VEVENT
                BEGIN:VEVENT
                UID:planning
                DTSTAMP:20261101T120000Z
                DTSTART:20261105T100000
                DTEND:20261105T110000
                SUMMARY:Planning
                END:VEVENT
                END:VCALENDAR
                """.replace("\n", "\r\n");

        assertThat(ics.parse(calendar.getBytes(StandardCharsets.UTF_8)).events())
                .extracting(CalendarEvent::title).containsExactly("Planning");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> ics.parse("hello, not a calendar".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidCalendarException.class);
        assertThatThrownBy(() -> ics.parse(new byte[0]))
                .isInstanceOf(InvalidCalendarException.class);
    }
}
