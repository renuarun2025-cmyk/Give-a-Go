package com.giveago.reschedulefairy.web;

import java.time.LocalDateTime;
import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record TargetWindowRequest(String date, String start, String end) {
    }

    public record EventDto(String uid, String title, LocalDateTime start, LocalDateTime end, boolean movable) {
    }

    public record AfterEventDto(String uid, String title, LocalDateTime start, LocalDateTime end, boolean movable,
                                boolean moved) {
    }

    public record MoveDto(String uid, String title, LocalDateTime from, LocalDateTime to) {
    }

    public record UnplaceableDto(String uid, String title, String reason) {
    }

    public record RescheduleResponse(List<EventDto> before, List<AfterEventDto> after, List<MoveDto> moves,
                                     List<UnplaceableDto> unplaceable, String icsDownloadId) {
    }

    public record ApiError(int status, String error) {
    }
}
