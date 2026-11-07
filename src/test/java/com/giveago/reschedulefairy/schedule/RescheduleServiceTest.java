package com.giveago.reschedulefairy.schedule;

import com.giveago.reschedulefairy.model.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescheduleServiceTest {

    private static final LocalDate THU = LocalDate.of(2026, 11, 5);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 6);
    private static final LocalDate MON = LocalDate.of(2026, 11, 9);

    private final RescheduleService service = new RescheduleService();

    private static CalendarEvent event(String uid, LocalDate day, String start, String end, boolean movable) {
        return new CalendarEvent(uid, uid, day.atTime(LocalTime.parse(start)), day.atTime(LocalTime.parse(end)), movable);
    }

    private static TimeWindow window(LocalDate day, String start, String end) {
        return new TimeWindow(day, LocalTime.parse(start), LocalTime.parse(end));
    }

    private static Map<String, CalendarEvent> byUid(List<CalendarEvent> events) {
        return events.stream().collect(Collectors.toMap(CalendarEvent::uid, Function.identity()));
    }

    private static List<CalendarEvent> thursdayAndFriday() {
        return List.of(
                event("kickoff", THU, "09:00", "10:00", false),
                event("roadmap", THU, "10:15", "11:45", true),
                event("one-on-one", THU, "12:00", "12:30", true),
                event("board", THU, "14:00", "15:00", false),
                event("design", THU, "15:30", "16:30", true),
                event("standup", FRI, "09:30", "10:00", true),
                event("demo", FRI, "14:00", "15:00", true),
                event("wrapup", FRI, "16:30", "17:00", true),
                event("customer", MON, "13:00", "14:00", false),
                event("budget", MON, "15:30", "16:30", true));
    }

    @Test
    void movesMovableEventsWithoutAnyOverlapIncludingBuffer() {
        int buffer = 15;
        RescheduleResult result = service.reschedule(thursdayAndFriday(), THU,
                List.of(window(FRI, "09:00", "12:00"), window(FRI, "13:00", "17:00"), window(MON, "13:00", "17:00")),
                WorkingHours.DEFAULT, buffer);

        assertThat(result.moves()).hasSize(3);
        assertThat(result.unplaceable()).isEmpty();
        List<CalendarEvent> after = result.after();
        for (int i = 0; i < after.size(); i++) {
            for (int j = i + 1; j < after.size(); j++) {
                CalendarEvent a = after.get(i);
                CalendarEvent b = after.get(j);
                boolean involvesMoved = result.moves().stream()
                        .anyMatch(m -> m.uid().equals(a.uid()) || m.uid().equals(b.uid()));
                Duration gap = involvesMoved ? Duration.ofMinutes(buffer) : Duration.ZERO;
                boolean overlaps = a.start().isBefore(b.end().plus(gap)) && b.start().isBefore(a.end().plus(gap));
                assertThat(overlaps).as("%s vs %s", a, b).isFalse();
            }
        }
    }

    @Test
    void placesLongestFirstInEarliestFreeSlot() {
        RescheduleResult result = service.reschedule(thursdayAndFriday(), THU,
                List.of(window(FRI, "13:00", "17:00"), window(MON, "13:00", "17:00")),
                WorkingHours.DEFAULT, 15);

        Map<String, CalendarEvent> after = byUid(result.after());
        assertThat(after.get("design").start()).isEqualTo(FRI.atTime(15, 15));
        assertThat(after.get("one-on-one").start()).isEqualTo(FRI.atTime(13, 0));
        assertThat(result.unplaceable()).extracting(RescheduleResult.Unplaceable::uid).containsExactly("roadmap");
    }

    @Test
    void keepsOriginalDurations() {
        List<CalendarEvent> events = thursdayAndFriday();
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "09:00", "12:00"), window(FRI, "13:00", "17:00"), window(MON, "13:00", "17:00")),
                WorkingHours.DEFAULT, 15);

        Map<String, CalendarEvent> before = byUid(events);
        Map<String, CalendarEvent> after = byUid(result.after());
        assertThat(after).hasSameSizeAs(before);
        before.forEach((uid, original) -> assertThat(after.get(uid).duration()).isEqualTo(original.duration()));
    }

    @Test
    void respectsWorkingHoursEvenIfWindowIsWider() {
        List<CalendarEvent> events = List.of(event("early", THU, "08:00", "09:00", true));
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "06:00", "20:00")), WorkingHours.DEFAULT, 0);

        CalendarEvent moved = result.after().get(0);
        assertThat(moved.start()).isEqualTo(FRI.atTime(9, 0));

        RescheduleResult late = service.reschedule(List.of(event("long", THU, "10:00", "12:00", true)), THU,
                List.of(window(FRI, "16:00", "20:00")), WorkingHours.DEFAULT, 0);
        assertThat(late.moves()).isEmpty();
        assertThat(late.unplaceable()).singleElement().satisfies(u -> assertThat(u.reason()).contains("longer"));
    }

    @Test
    void respectsBufferAroundExistingAndPlacedEvents() {
        List<CalendarEvent> events = List.of(
                event("a", THU, "10:00", "11:00", true),
                event("b", THU, "11:00", "12:00", true),
                event("existing", FRI, "13:00", "13:30", false));
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "13:00", "17:00")), WorkingHours.DEFAULT, 20);

        Map<String, CalendarEvent> after = byUid(result.after());
        assertThat(after.get("a").start()).isEqualTo(FRI.atTime(13, 50));
        assertThat(after.get("b").start()).isEqualTo(FRI.atTime(15, 10));
    }

    @Test
    void fixedEventsNeverMove() {
        List<CalendarEvent> events = thursdayAndFriday();
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "09:00", "12:00"), window(FRI, "13:00", "17:00"), window(MON, "13:00", "17:00")),
                WorkingHours.DEFAULT, 15);

        Map<String, CalendarEvent> after = byUid(result.after());
        events.stream().filter(e -> !e.movable())
                .forEach(fixed -> assertThat(after.get(fixed.uid())).isEqualTo(fixed));
        assertThat(result.moves()).extracting(RescheduleResult.Move::uid).doesNotContain("kickoff", "board");
    }

    @Test
    void onlyMovesEventsFromTheSourceDay() {
        RescheduleResult result = service.reschedule(thursdayAndFriday(), THU,
                List.of(window(MON, "09:00", "17:00")), WorkingHours.DEFAULT, 15);

        assertThat(result.moves()).extracting(RescheduleResult.Move::uid)
                .containsExactlyInAnyOrder("roadmap", "one-on-one", "design");
        assertThat(byUid(result.after()).get("standup").start()).isEqualTo(FRI.atTime(9, 30));
    }

    @Test
    void reportsUnplaceableEventsWhenWindowsAreFull() {
        List<CalendarEvent> events = List.of(
                event("x", THU, "10:00", "11:00", true),
                event("y", THU, "11:00", "11:30", true),
                event("blocker", FRI, "13:00", "17:00", false));
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "13:00", "17:00")), WorkingHours.DEFAULT, 15);

        assertThat(result.moves()).isEmpty();
        assertThat(result.unplaceable()).extracting(RescheduleResult.Unplaceable::uid).containsExactly("x", "y");
        assertThat(result.unplaceable()).allSatisfy(u -> assertThat(u.reason()).contains("No free"));
        assertThat(result.after()).containsExactlyInAnyOrderElementsOf(events);
    }

    @Test
    void unplaceableOvernightEventStillBlocksItsTargetDayHours() {
        List<CalendarEvent> events = List.of(
                new CalendarEvent("overnight", "overnight", THU.atTime(22, 0), FRI.atTime(10, 0), true),
                event("short", THU, "10:00", "11:00", true));
        RescheduleResult result = service.reschedule(events, THU,
                List.of(window(FRI, "09:00", "12:00")), WorkingHours.DEFAULT, 0);

        assertThat(result.unplaceable()).extracting(RescheduleResult.Unplaceable::uid).containsExactly("overnight");
        assertThat(byUid(result.after()).get("short").start()).isEqualTo(FRI.atTime(10, 0));
    }

    @Test
    void rejectsTargetWindowOnTheSourceDay() {
        assertThatThrownBy(() -> service.reschedule(thursdayAndFriday(), THU,
                List.of(window(THU, "13:00", "17:00")), WorkingHours.DEFAULT, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movesAreReportedWithFromAndTo() {
        RescheduleResult result = service.reschedule(thursdayAndFriday(), THU,
                List.of(window(FRI, "13:00", "17:00"), window(MON, "13:00", "17:00")), WorkingHours.DEFAULT, 15);

        assertThat(result.moves()).anySatisfy(m -> {
            assertThat(m.uid()).isEqualTo("design");
            assertThat(m.from()).isEqualTo(LocalDateTime.of(2026, 11, 5, 15, 30));
            assertThat(m.to()).isEqualTo(LocalDateTime.of(2026, 11, 6, 15, 15));
        });
    }
}
