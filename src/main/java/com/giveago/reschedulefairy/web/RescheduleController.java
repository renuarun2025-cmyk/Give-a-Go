package com.giveago.reschedulefairy.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.giveago.reschedulefairy.ics.IcsCalendarService;
import com.giveago.reschedulefairy.ics.ParsedCalendar;
import com.giveago.reschedulefairy.model.CalendarEvent;
import com.giveago.reschedulefairy.schedule.RescheduleResult;
import com.giveago.reschedulefairy.schedule.RescheduleService;
import com.giveago.reschedulefairy.schedule.TimeWindow;
import com.giveago.reschedulefairy.schedule.WorkingHours;
import com.giveago.reschedulefairy.web.ApiDtos.AfterEventDto;
import com.giveago.reschedulefairy.web.ApiDtos.EventDto;
import com.giveago.reschedulefairy.web.ApiDtos.MoveDto;
import com.giveago.reschedulefairy.web.ApiDtos.RescheduleResponse;
import com.giveago.reschedulefairy.web.ApiDtos.TargetWindowRequest;
import com.giveago.reschedulefairy.web.ApiDtos.UnplaceableDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reschedule")
public class RescheduleController {

    static final int MAX_BUFFER_MINUTES = 240;
    private static final MediaType TEXT_CALENDAR = MediaType.parseMediaType("text/calendar;charset=UTF-8");

    private final IcsCalendarService icsCalendarService;
    private final RescheduleService rescheduleService;
    private final IcsResultStore store;
    private final ObjectMapper objectMapper;

    public RescheduleController(IcsCalendarService icsCalendarService, RescheduleService rescheduleService,
                                IcsResultStore store, ObjectMapper objectMapper) {
        this.icsCalendarService = icsCalendarService;
        this.rescheduleService = rescheduleService;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RescheduleResponse reschedule(@RequestParam("file") MultipartFile file,
                                         @RequestParam("sourceDay") String sourceDay,
                                         @RequestParam("targetWindows") String targetWindows,
                                         @RequestParam(value = "bufferMinutes", defaultValue = "15") String bufferMinutes)
            throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("Please upload a non-empty .ics file");
        }
        LocalDate day = parseDate(sourceDay, "sourceDay");
        List<TimeWindow> windows = parseWindows(targetWindows);
        int buffer = parseBuffer(bufferMinutes);

        ParsedCalendar parsed = icsCalendarService.parse(file.getBytes());
        RescheduleResult result = rescheduleService.reschedule(parsed.events(), day, windows, WorkingHours.DEFAULT, buffer);
        byte[] updated = icsCalendarService.write(parsed, result.moves());
        String downloadId = store.save(updated, downloadName(file.getOriginalFilename()));

        Set<String> movedUids = result.moves().stream().map(RescheduleResult.Move::uid).collect(Collectors.toSet());
        return new RescheduleResponse(
                parsed.events().stream()
                        .sorted(Comparator.comparing(CalendarEvent::start))
                        .map(e -> new EventDto(e.uid(), e.title(), e.start(), e.end(), e.movable()))
                        .toList(),
                result.after().stream()
                        .map(e -> new AfterEventDto(e.uid(), e.title(), e.start(), e.end(), e.movable(),
                                movedUids.contains(e.uid())))
                        .toList(),
                result.moves().stream().map(m -> new MoveDto(m.uid(), m.title(), m.from(), m.to())).toList(),
                result.unplaceable().stream().map(u -> new UnplaceableDto(u.uid(), u.title(), u.reason())).toList(),
                downloadId);
    }

    @GetMapping("/{icsDownloadId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String icsDownloadId) {
        IcsResultStore.StoredIcs stored = store.find(icsDownloadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No rescheduled calendar with id " + icsDownloadId));
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(stored.filename()).build().toString())
                .body(stored.content());
    }

    private List<TimeWindow> parseWindows(String json) {
        List<TargetWindowRequest> requested;
        try {
            requested = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new BadRequestException(
                    "targetWindows must be a JSON array like [{\"date\":\"2026-11-06\",\"start\":\"13:00\",\"end\":\"17:00\"}]");
        }
        if (requested == null || requested.isEmpty()) {
            throw new BadRequestException("Pick at least one target window");
        }
        return requested.stream().map(w -> {
            if (w == null) {
                throw new BadRequestException("targetWindows must not contain null entries");
            }
            LocalDate date = parseDate(w.date(), "targetWindows.date");
            LocalTime start = parseTime(w.start(), "targetWindows.start");
            LocalTime end = parseTime(w.end(), "targetWindows.end");
            if (!end.isAfter(start)) {
                throw new BadRequestException("Target window on " + date + " must end after it starts");
            }
            return new TimeWindow(date, start, end);
        }).toList();
    }

    private static int parseBuffer(String value) {
        try {
            int buffer = Integer.parseInt(value.trim());
            if (buffer < 0 || buffer > MAX_BUFFER_MINUTES) {
                throw new BadRequestException("bufferMinutes must be between 0 and " + MAX_BUFFER_MINUTES);
            }
            return buffer;
        } catch (NumberFormatException e) {
            throw new BadRequestException("bufferMinutes must be a whole number");
        }
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be a date like 2026-11-05, got '" + value + "'");
        }
    }

    private static LocalTime parseTime(String value, String field) {
        try {
            return LocalTime.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be a time like 13:00, got '" + value + "'");
        }
    }

    private static String downloadName(String originalFilename) {
        String base = originalFilename == null || originalFilename.isBlank() ? "calendar" : originalFilename;
        base = base.replaceAll("(?i)\\.ics$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return base + "-rescheduled.ics";
    }
}
