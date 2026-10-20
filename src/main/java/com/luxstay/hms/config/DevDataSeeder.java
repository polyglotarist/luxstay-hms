package com.luxstay.hms.config;

import com.luxstay.hms.entity.AppUser;
import com.luxstay.hms.enums.Role;
import com.luxstay.hms.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DevDataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        for (Role role : Role.values()) {
            String email = role.name().toLowerCase() + "@luxstay.test";
            if (!userRepository.existsByEmail(email)) {
                AppUser user = new AppUser();
                user.setEmail(email);
                user.setPasswordHash(passwordEncoder.encode("Admin123!"));
                user.setRole(role);
                userRepository.save(user);
                log.info("Seeded user {}", email);
            }
        }
    }
}