# Reschedule Fairy

Out sick? Upload your calendar (`.ics`), pick the day you're out, and Reschedule Fairy moves your
movable meetings into free slots on other days. It shows a Before/After week view and lets you
download the updated `.ics`.

## Run it

Requires Java 21.

```bash
./mvnw spring-boot:run      # then open http://localhost:8080
./mvnw test                 # unit, ics round-trip and MockMvc tests
```

In the UI, click **Use sample calendar** (`src/main/resources/static/sample.ics`), keep the defaults
and press **I'm sick — reschedule**.

## How it works

**Fixed vs movable.** An event is *fixed* if its title contains `[fixed]` or if any ATTENDEE has an
email domain different from the calendar owner's domain (the most common ORGANIZER domain in the
file). Recurring events (RRULE/RDATE, and their RECURRENCE-ID exceptions) are fixed, and their
occurrences up to a month past the calendar are expanded so nothing is moved on top of them;
all-day and cancelled events are ignored.
Everything else is *movable*.

**Algorithm** (`RescheduleService`, pure Java):

1. Take the movable events on the day you're out and sort them longest first.
2. Clip every target window to working hours (09:00–17:00) and sort windows chronologically.
3. For each event, scan the windows for the earliest start where the event (with its original
   duration) fits without overlapping any existing or already-placed event, keeping
   `bufferMinutes` of space on both sides of every other event.
4. If no slot fits, the event stays where it was and is reported as unplaceable with a reason.

The updated `.ics` is the original file with only DTSTART/DTEND of moved events changed (time zones
are preserved).

## API

`POST /api/reschedule` (multipart):

| field           | example                                                          |
|-----------------|------------------------------------------------------------------|
| `file`          | `calendar.ics`                                                   |
| `sourceDay`     | `2026-11-05`                                                     |
| `targetWindows` | `[{"date":"2026-11-06","start":"13:00","end":"17:00"}]`          |
| `bufferMinutes` | `15` (optional, default 15, 0–240)                               |

Returns `before`, `after`, `moves`, `unplaceable` and `icsDownloadId` (times are ISO-8601 local
date-times). Invalid files or inputs return `400` with `{"status":400,"error":"..."}`.

`GET /api/reschedule/{icsDownloadId}/download` returns the updated `.ics` as an attachment.
Results are kept in memory (most recent 200, max 20 MB in total).
