package com.luxstay.hms.service.impl;

import com.luxstay.hms.dto.request.LoginRequest;
import com.luxstay.hms.dto.response.LoginResponse;
import com.luxstay.hms.entity.AppUser;
import com.luxstay.hms.repository.AppUserRepository;
import com.luxstay.hms.security.TokenService;
import com.luxstay.hms.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @Override
    public LoginResponse login(LoginRequest request) {
        AppUser user = userRepository.findByEmail(request.email())
                .filter(AppUser::isEnabled)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        log.info("User {} logged in", user.getId());
        return tokenService.issue(user);
    }
}