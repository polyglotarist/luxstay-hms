package com.luxstay.hms.dto.response;

import java.math.BigDecimal;

public record RoomTypeResponse(
        Long id,
        String code,
        String name,
        Integer maxOccupancy,
        BigDecimal baseRate,
        String description) {
}