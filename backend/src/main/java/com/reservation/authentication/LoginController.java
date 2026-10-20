package com.reservation.authentication;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    public LoginController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {

        var token = UsernamePasswordAuthenticationToken.unauthenticated(
                request.email(),
                request.password()
        );

        Authentication authentication =
                authenticationManager.authenticate(token);

        // Меняем идентификатор существующей сессии.
        httpRequest.getSession();
        httpRequest.changeSessionId();

// Старый CSRF-токен больше не должен использоваться.
        csrfTokenRepository.saveToken(null, httpRequest, httpResponse);

// Создаём контекст успешной аутентификации.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

// Делаем авторизацию доступной в текущем запросе.
        SecurityContextHolder.setContext(context);

// Сохраняем авторизацию для следующих HTTP-запросов.
        securityContextRepository.saveContext(
                context,
                httpRequest,
                httpResponse
        );

        return new LoginResponse(
                authentication.getName(),
                authentication.getAuthorities().iterator().next().getAuthority()
        );
    }

    @GetMapping("/me")
    public LoginResponse me(Authentication authentication) {
        return new LoginResponse(
                authentication.getName(),
                authentication.getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority()
        );
    }
}