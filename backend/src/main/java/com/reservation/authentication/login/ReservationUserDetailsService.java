package com.reservation.authentication.login;

import com.reservation.users.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ReservationUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public ReservationUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        var user = userRepository.findByNormalizedEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Invalid credentials"
                ));

        boolean accountEnabled = user.isEnabled()
                && user.getDeletedAt() == null
                && user.isEmailVerified();

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities("ROLE_" + user.getRole().name())
                .disabled(!accountEnabled)
                .build();
    }
}