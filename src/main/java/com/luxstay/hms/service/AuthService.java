package com.luxstay.hms.service;

import com.luxstay.hms.dto.request.LoginRequest;
import com.luxstay.hms.dto.response.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);
}
