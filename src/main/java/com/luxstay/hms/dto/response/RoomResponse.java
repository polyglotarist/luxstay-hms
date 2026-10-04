package com.luxstay.hms.dto.response;

import com.luxstay.hms.enums.RoomStatus;

public record RoomResponse(
        Long id,
        String number,
        Integer floor,
        String viewType,
        RoomStatus status,
        String roomTypeCode,
        String roomTypeName) {
}