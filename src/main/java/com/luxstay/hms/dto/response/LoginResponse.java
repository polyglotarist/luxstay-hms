package com.luxstay.hms.dto.response;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {

}
