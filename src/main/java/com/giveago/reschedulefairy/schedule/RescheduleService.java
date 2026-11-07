package com.giveago.reschedulefairy.schedule;

import com.giveago.reschedulefairy.model.CalendarEvent;
import com.giveago.reschedulefairy.schedule.RescheduleResult.Move;
import com.giveago.reschedulefairy.schedule.RescheduleResult.Unplaceable;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves the movable events of one day into the earliest free slots of the given target windows.
 * Pure logic: no I/O and no framework dependencies.
 */
public class RescheduleService {

    public RescheduleResult reschedule(List<CalendarEvent> events,
                                       LocalDate sourceDay,
                                       List<TimeWindow> targetWindows,
                                       WorkingHours workingHours,
                                       int bufferMinutes) {
        if (bufferMinutes < 0) {
            throw new IllegalArgumentException("bufferMinutes must not be negative");
        }
        for (TimeWindow window : targetWindows) {
            if (window.date().equals(sourceDay)) {
                throw new IllegalArgumentException("Target window " + window.date() + " is the day you're out");
            }
        }

        Duration buffer = Duration.ofMinutes(bufferMinutes);
        List<TimeWindow> windows = clipToWorkingHours(targetWindows, workingHours);

        List<CalendarEvent> toMove = events.stream()
                .filter(e -> e.movable() && e.start().toLocalDate().equals(sourceDay))
                .sorted(Comparator.comparing(CalendarEvent::duration).reversed()
                        .thenComparing(CalendarEvent::start)
                        .thenComparing(CalendarEvent::uid))
                .toList();

        List<CalendarEvent> busy = new ArrayList<>(events.stream().filter(e -> !toMove.contains(e)).toList());
        Map<CalendarEvent, CalendarEvent> placed = new HashMap<>();
        List<Move> moves = new ArrayList<>();
        List<Unplaceable> unplaceable = new ArrayList<>();

        for (CalendarEvent event : toMove) {
            Optional<LocalDateTime> slot = findEarliestSlot(event.duration(), windows, busy, buffer);
            if (slot.isPresent()) {
                CalendarEvent moved = event.withStart(slot.get());
                busy.add(moved);
                placed.put(event, moved);
                moves.add(new Move(event.uid(), event.title(), event.start(), moved.start()));
            } else {
                unplaceable.add(new Unplaceable(event.uid(), event.title(), reasonFor(event, windows, bufferMinutes)));
            }
        }

        List<CalendarEvent> after = events.stream()
                .map(e -> placed.getOrDefault(e, e))
                .sorted(Comparator.comparing(CalendarEvent::start).thenComparing(CalendarEvent::uid))
                .toList();
        moves.sort(Comparator.comparing(Move::to));
        return new RescheduleResult(after, List.copyOf(moves), List.copyOf(unplaceable));
    }

    private static List<TimeWindow> clipToWorkingHours(List<TimeWindow> windows, WorkingHours hours) {
        return windows.stream()
                .map(w -> {
                    LocalTime start = w.start().isBefore(hours.start()) ? hours.start() : w.start();
                    LocalTime end = w.end().isAfter(hours.end()) ? hours.end() : w.end();
                    return end.isAfter(start) ? new TimeWindow(w.date(), start, end) : null;
                })
                .filter(w -> w != null)
                .sorted(Comparator.comparing(TimeWindow::startDateTime))
                .toList();
    }

    private static Optional<LocalDateTime> findEarliestSlot(Duration duration, List<TimeWindow> windows,
                                                            List<CalendarEvent> busy, Duration buffer) {
        for (TimeWindow window : windows) {
            LocalDateTime windowStart = window.startDateTime();
            LocalDateTime windowEnd = window.endDateTime();
            List<CalendarEvent> blockers = busy.stream()
                    .filter(e -> e.start().minus(buffer).isBefore(windowEnd) && e.end().plus(buffer).isAfter(windowStart))
                    .sorted(Comparator.comparing(CalendarEvent::start))
                    .toList();

            LocalDateTime candidate = windowStart;
            for (CalendarEvent blocker : blockers) {
                if (!candidate.plus(duration).isAfter(blocker.start().minus(buffer))) {
                    break;
                }
                LocalDateTime afterBlocker = blocker.end().plus(buffer);
                if (afterBlocker.isAfter(candidate)) {
                    candidate = afterBlocker;
                }
            }
            if (!candidate.plus(duration).isAfter(windowEnd)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String reasonFor(CalendarEvent event, List<TimeWindow> windows, int bufferMinutes) {
        long minutes = event.duration().toMinutes();
        if (windows.isEmpty()) {
            return "No target window falls inside working hours";
        }
        long longestWindow = windows.stream()
                .mapToLong(w -> Duration.between(w.start(), w.end()).toMinutes())
                .max()
                .orElse(0);
        if (minutes > longestWindow) {
            return "At " + minutes + " min it is longer than every target window (longest is " + longestWindow + " min)";
        }
        return "No free " + minutes + "-min slot left in the target windows (with a " + bufferMinutes + "-min buffer)";
    }
}
