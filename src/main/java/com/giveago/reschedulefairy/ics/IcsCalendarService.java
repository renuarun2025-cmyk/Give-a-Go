package com.giveago.reschedulefairy.ics;

import com.giveago.reschedulefairy.model.CalendarEvent;
import com.giveago.reschedulefairy.schedule.RescheduleResult.Move;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.TzId;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Uid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads .ics files into {@link CalendarEvent}s and writes moved events back into the original calendar.
 *
 * <p>An event is FIXED when its title contains "[fixed]" or when it has an attendee whose email domain differs
 * from the calendar owner's domain (the most common ORGANIZER domain in the file), or when it recurs. Later
 * occurrences of recurring series are added as fixed events. All-day and cancelled events are ignored.
 */
public class IcsCalendarService {

    private static final String FIXED_MARKER = "[fixed]";
    private static final int RECURRENCE_HORIZON_DAYS = 31;
    private static final int MAX_OCCURRENCES_PER_SERIES = 500;

    public ParsedCalendar parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidCalendarException("The uploaded file is empty");
        }
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new ByteArrayInputStream(content));
        } catch (ParserException e) {
            throw new InvalidCalendarException("Not a valid .ics calendar: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new InvalidCalendarException("Could not read the .ics file: " + e.getMessage(), e);
        }

        List<VEvent> vevents = calendar.getComponents(Component.VEVENT);
        ZoneId zone = displayZone(calendar, vevents);
        Optional<String> ownerDomain = mostCommon(vevents.stream()
                .flatMap(v -> v.<Organizer>getProperties(Property.ORGANIZER).stream())
                .map(o -> domainOf(o.getCalAddress()))
                .flatMap(Optional::stream)
                .toList());

        Set<String> usedUids = vevents.stream()
                .map(v -> v.getUid().map(Uid::getValue).orElse(""))
                .filter(v -> !v.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        List<CalendarEvent> events = new ArrayList<>();
        Map<String, VEvent> components = new LinkedHashMap<>();
        for (VEvent vevent : vevents) {
            Optional<CalendarEvent> event = toEvent(vevent, zone, ownerDomain, usedUids, components.keySet());
            if (event.isPresent()) {
                events.add(event.get());
                components.put(event.get().uid(), vevent);
            }
        }
        if (events.isEmpty()) {
            throw new InvalidCalendarException("The calendar contains no timed events");
        }
        events.addAll(expandRecurrences(events, components, vevents, zone));
        return new ParsedCalendar(calendar, List.copyOf(events), components, zone);
    }

    public byte[] write(ParsedCalendar parsed, List<Move> moves) {
        for (Move move : moves) {
            VEvent vevent = parsed.components().get(move.uid());
            if (vevent == null) {
                throw new IllegalArgumentException("Unknown event uid " + move.uid());
            }
            DateProperty<Temporal> start = vevent.<Temporal>getStartDate().orElseThrow();
            Optional<DateProperty<Temporal>> end = vevent.<Temporal>getEndDate(false).map(e -> e);
            LocalDateTime oldStart = toLocal(start.getDate(), parsed.zone());
            Duration shift = Duration.between(oldStart, move.to());
            setLocal(start, move.to(), parsed.zone());
            end.ifPresent(e -> setLocal(e, toLocal(e.getDate(), parsed.zone()).plus(shift), parsed.zone()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new CalendarOutputter(false).output(parsed.calendar(), out);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write calendar", e);
        }
        return out.toByteArray();
    }

    private static void setLocal(DateProperty<Temporal> property, LocalDateTime local, ZoneId zone) {
        Optional<TzId> originalTzId = property.getParameter(Parameter.TZID);
        property.setDate(fromLocal(local, property.getDate(), zone));
        originalTzId.ifPresent(property::replace);
    }

    private static Optional<CalendarEvent> toEvent(VEvent vevent, ZoneId zone, Optional<String> ownerDomain,
                                                   Set<String> calendarUids, Collection<String> usedUids) {
        Optional<DateProperty<Temporal>> startProp = vevent.<Temporal>getStartDate().map(s -> s);
        if (startProp.isEmpty() || startProp.get().getDate() instanceof LocalDate) {
            return Optional.empty();
        }
        boolean cancelled = vevent.getProperty(Property.STATUS)
                .map(s -> "CANCELLED".equalsIgnoreCase(s.getValue())).orElse(false);
        if (cancelled) {
            return Optional.empty();
        }
        LocalDateTime start = toLocal(startProp.get().getDate(), zone);
        LocalDateTime end;
        Optional<DateProperty<Temporal>> endProp = vevent.<Temporal>getEndDate(false).map(e -> e);
        if (endProp.isPresent()) {
            end = toLocal(endProp.get().getDate(), zone);
        } else {
            Optional<Property> duration = vevent.getProperty(Property.DURATION);
            if (duration.isEmpty()) {
                return Optional.empty();
            }
            end = start.plus(Duration.parse(duration.get().getValue()));
        }
        if (!end.isAfter(start)) {
            return Optional.empty();
        }

        String title = vevent.getProperty(Property.SUMMARY).map(Property::getValue).orElse("(untitled)");
        String uid = uniqueUid(vevent, calendarUids, usedUids);
        boolean recurring = vevent.getProperty(Property.RRULE).isPresent()
                || vevent.getProperty(Property.RDATE).isPresent()
                || vevent.getProperty(Property.RECURRENCE_ID).isPresent();
        boolean external = ownerDomain.isPresent() && vevent.<Attendee>getProperties(Property.ATTENDEE).stream()
                .map(a -> domainOf(a.getCalAddress()))
                .flatMap(Optional::stream)
                .anyMatch(domain -> !domain.equals(ownerDomain.get()));
        boolean markedFixed = title.toLowerCase(Locale.ROOT).contains(FIXED_MARKER);
        return Optional.of(new CalendarEvent(uid, title, start, end, !(external || markedFixed || recurring)));
    }

    /**
     * Adds the later occurrences of recurring series as fixed events so that nothing is moved on top of them.
     * The range covers the calendar's events plus a few weeks, which includes any realistic target window.
     */
    private static List<CalendarEvent> expandRecurrences(List<CalendarEvent> events, Map<String, VEvent> components,
                                                         List<VEvent> vevents, ZoneId zone) {
        LocalDateTime rangeStart = events.stream().map(CalendarEvent::start).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime rangeEnd = events.stream().map(CalendarEvent::end).max(LocalDateTime::compareTo).orElseThrow()
                .plusDays(RECURRENCE_HORIZON_DAYS);
        Set<String> overridden = vevents.stream()
                .filter(v -> v.getUid().isPresent())
                .flatMap(v -> v.<DateProperty<Temporal>>getProperty(Property.RECURRENCE_ID).stream()
                        .map(r -> v.getUid().get().getValue() + "|" + toLocal(r.getDate(), zone)))
                .collect(Collectors.toSet());

        Map<String, CalendarEvent> byUid = events.stream()
                .collect(Collectors.toMap(CalendarEvent::uid, Function.identity()));
        List<CalendarEvent> occurrences = new ArrayList<>();
        components.forEach((uid, vevent) -> {
            if (vevent.getProperty(Property.RRULE).isEmpty() && vevent.getProperty(Property.RDATE).isEmpty()) {
                return;
            }
            CalendarEvent master = byUid.get(uid);
            String rawUid = vevent.getUid().map(Uid::getValue).orElse(uid);
            occurrencesOf(vevent, rangeStart, rangeEnd, zone).stream()
                    .filter(o -> !o.start().equals(master.start()))
                    .filter(o -> !overridden.contains(rawUid + "|" + o.start()))
                    .limit(MAX_OCCURRENCES_PER_SERIES)
                    .map(o -> new CalendarEvent(uid + "@" + o.start(), master.title(), o.start(), o.end(), false))
                    .forEach(occurrences::add);
        });
        return occurrences;
    }

    private record Occurrence(LocalDateTime start, LocalDateTime end) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Occurrence> occurrencesOf(VEvent vevent, LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                                  ZoneId zone) {
        try {
            Temporal seriesStart = vevent.getStartDate().orElseThrow().getDate();
            Period range = new Period(fromLocal(rangeStart, seriesStart, zone), fromLocal(rangeEnd, seriesStart, zone));
            Set<Period<Temporal>> periods = vevent.calculateRecurrenceSet(range);
            return periods.stream()
                    .map(p -> new Occurrence(toLocal(p.getStart(), zone), toLocal(p.getEnd(), zone)))
                    .filter(o -> o.end().isAfter(o.start()))
                    .sorted(Comparator.comparing(Occurrence::start))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String uniqueUid(VEvent vevent, Set<String> calendarUids, Collection<String> usedUids) {
        Optional<String> existing = vevent.getUid().map(Uid::getValue).filter(v -> !v.isBlank());
        if (existing.isEmpty()) {
            String generated;
            do {
                generated = "reschedule-fairy-" + UUID.randomUUID();
            } while (calendarUids.contains(generated));
            calendarUids.add(generated);
            vevent.replace(new Uid(generated));
            return generated;
        }
        String uid = existing.get();
        String candidate = uid;
        for (int n = 2; usedUids.contains(candidate); n++) {
            candidate = uid + "#" + n;
        }
        return candidate;
    }

    private static ZoneId displayZone(Calendar calendar, List<VEvent> vevents) {
        Optional<ZoneId> fromHeader = calendar.getProperty("X-WR-TIMEZONE").map(Property::getValue).flatMap(IcsCalendarService::zoneOf);
        if (fromHeader.isPresent()) {
            return fromHeader.get();
        }
        return mostCommon(vevents.stream()
                .map(v -> v.getStartDate().flatMap(s -> s.<TzId>getParameter(Parameter.TZID)))
                .flatMap(Optional::stream)
                .map(TzId::getValue)
                .toList())
                .flatMap(IcsCalendarService::zoneOf)
                .orElse(ZoneId.systemDefault());
    }

    private static Optional<ZoneId> zoneOf(String id) {
        try {
            return Optional.of(ZoneId.of(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static LocalDateTime toLocal(Temporal temporal, ZoneId zone) {
        return switch (temporal) {
            case LocalDateTime ldt -> ldt;
            case ZonedDateTime zdt -> zdt.withZoneSameInstant(zone).toLocalDateTime();
            case OffsetDateTime odt -> odt.atZoneSameInstant(zone).toLocalDateTime();
            case Instant instant -> instant.atZone(zone).toLocalDateTime();
            default -> throw new InvalidCalendarException("Unsupported date value: " + temporal);
        };
    }

    static Temporal fromLocal(LocalDateTime local, Temporal original, ZoneId zone) {
        return switch (original) {
            case LocalDateTime ignored -> local;
            case ZonedDateTime zdt -> local.atZone(zone).withZoneSameInstant(zdt.getZone());
            case OffsetDateTime odt -> local.atZone(zone).toOffsetDateTime().withOffsetSameInstant(odt.getOffset());
            case Instant ignored -> local.atZone(zone).toInstant();
            default -> throw new IllegalArgumentException("Unsupported date value: " + original);
        };
    }

    private static Optional<String> domainOf(URI calAddress) {
        if (calAddress == null) {
            return Optional.empty();
        }
        String address = calAddress.getSchemeSpecificPart();
        if (address == null) {
            return Optional.empty();
        }
        int at = address.lastIndexOf('@');
        return at >= 0 && at < address.length() - 1
                ? Optional.of(address.substring(at + 1).trim().toLowerCase(Locale.ROOT))
                : Optional.empty();
    }

    private static <T> Optional<T> mostCommon(List<T> values) {
        return values.stream()
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }
}
