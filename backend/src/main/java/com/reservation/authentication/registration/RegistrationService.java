package com.reservation.authentication.registration;

import com.reservation.users.User;
import com.reservation.users.UserRepository;
import com.reservation.authentication.verification.EmailVerificationService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_lower_uk";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;

    public RegistrationService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, EmailVerificationService emailVerificationService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        if (userRepository.existsByNormalizedEmail(request.email())) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName()
        );

        try {
            // flush сразу выполняет INSERT, чтобы нарушение индекса проявилось здесь, а не при коммите.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Транзакция уже испорчена: больше не обращаемся к БД, только выбрасываем исключение,
            // и Spring откатывает транзакцию.
            if (isEmailUniqueViolation(e)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }

        String rawToken = emailVerificationService.createToken(user);

        return new RegistrationResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.isEmailVerified()
        );
    }

    private static boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
