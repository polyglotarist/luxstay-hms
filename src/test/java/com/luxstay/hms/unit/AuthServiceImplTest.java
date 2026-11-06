package com.luxstay.hms.unit;

import com.luxstay.hms.dto.request.LoginRequest;
import com.luxstay.hms.dto.response.LoginResponse;
import com.luxstay.hms.entity.AppUser;
import com.luxstay.hms.enums.Role;
import com.luxstay.hms.repository.AppUserRepository;
import com.luxstay.hms.security.TokenService;
import com.luxstay.hms.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenService tokenService;
    @InjectMocks AuthServiceImpl authService;

    @Test
    void login_withCorrectPassword_returnsToken() {
        AppUser user = user("hash");
        user.setRole(Role.MANAGER);
        when(userRepository.findByEmail("manager@luxstay.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Admin123!", "hash")).thenReturn(true);
        when(tokenService.issue(user)).thenReturn(new LoginResponse("jwt", "Bearer", 7200));

        LoginResponse response = authService.login(new LoginRequest("manager@luxstay.test", "Admin123!"));

        assertThat(response.accessToken()).isEqualTo("jwt");
    }

    @Test
    void login_withWrongPassword_throwsBadCredentials() {
        when(userRepository.findByEmail("manager@luxstay.test")).thenReturn(Optional.of(user("hash")));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("manager@luxstay.test", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_withUnknownEmail_throwsBadCredentials() {
        when(userRepository.findByEmail("nobody@luxstay.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@luxstay.test", "x")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_withDisabledUser_throwsBadCredentials() {
        AppUser user = user("hash");
        user.setEnabled(false);
        when(userRepository.findByEmail("manager@luxstay.test")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("manager@luxstay.test", "Admin123!")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(passwordEncoder, tokenService);
    }

    private static AppUser user(String passwordHash) {
        AppUser user = new AppUser();
        user.setEmail("manager@luxstay.test");
        user.setPasswordHash(passwordHash);
        return user;
    }
}