package com.luxstay.hms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email @Schema(example = "admin@luxstay.test") String email,
        @NotBlank @Schema(example = "Admin123!") String password) {
}