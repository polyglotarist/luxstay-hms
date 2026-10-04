package com.luxstay.hms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 10) @Schema(example = "613") String number,
        @NotBlank @Min(1) @Schema(example = "6") Integer floor,
        @Size(max = 30) @Schema(example = "SEA") String viewType,
        @NotNull @Schema(example = "1") Long roomTypeId) {

}
