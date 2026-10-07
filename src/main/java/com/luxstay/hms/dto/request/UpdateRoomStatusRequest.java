package com.luxstay.hms.dto.request;

import com.luxstay.hms.enums.RoomStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateRoomStatusRequest(@NotNull @Schema(example = "OUT_OF_ORDER") RoomStatus status) {
}
