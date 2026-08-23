package com.seatflow.user.presentation;

import com.seatflow.user.application.AuthService;
import com.seatflow.user.application.UserService;
import com.seatflow.user.presentation.dto.AuthDtos.AuthResponse;
import com.seatflow.user.presentation.dto.AuthDtos.LoginRequest;
import com.seatflow.user.presentation.dto.AuthDtos.RefreshRequest;
import com.seatflow.user.presentation.dto.AuthDtos.RegisterRequest;
import com.seatflow.user.presentation.dto.AuthDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authentication endpoints.
 * <p>
 * Controllers here do request mapping and nothing else. All decisions live in
 * the application services, which is what keeps this class from growing into the
 * place business logic hides.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * The current account. Doubles as the proof that a minted access token is
     * accepted by the resource server.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.findById(UUID.fromString(jwt.getSubject()));
    }

    /** Revokes every refresh token for the caller. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logoutEverywhere(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
