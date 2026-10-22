package com.reservation.authentication.login;

import com.reservation.users.User;
import com.reservation.users.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean checkCredentials(LoginRequest request) {
        User user = userRepository
                .findByNormalizedEmail(request.email())
                .orElse(null);

        if (user == null) {
            return false;
        }

        if (!user.isEnabled()) {
            return false;
        }

        if (user.getDeletedAt() != null) {
            return false;
        }

        if (!user.isEmailVerified()) {
            return false;
        }

        return passwordEncoder.matches(
                request.password(),
                user.getPasswordHash()
        );
    }
}