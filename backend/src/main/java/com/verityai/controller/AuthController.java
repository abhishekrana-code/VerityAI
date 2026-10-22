package com.verityai.controller;

import com.verityai.dto.request.ChangePasswordRequest;
import com.verityai.dto.request.LoginRequest;
import com.verityai.dto.request.RegisterRequest;
import com.verityai.dto.request.UpdateProfileRequest;
import com.verityai.dto.response.AuthResponse;
import com.verityai.dto.response.CsrfResponse;
import com.verityai.dto.response.UserResponse;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.UserRepository;
import com.verityai.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (userRepository.existsByEmailIgnoreCaseAndNotDeleted(request.getEmail())) {
            throw ApiException.conflict("Email already registered");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setRole("USER");

        userRepository.save(user);

        authenticateAndSave(request.getEmail(), request.getPassword(), httpRequest, httpResponse);

        return ResponseEntity.ok(new AuthResponse(toUserResponse(user), true));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authenticateAndSave(request.getEmail(), request.getPassword(), httpRequest, httpResponse);

        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
            .orElseThrow(() -> ApiException.unauthenticated("Invalid credentials"));

        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(toUserResponse(user), true));
    }

    private void authenticateAndSave(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, password)
        );

        org.springframework.security.core.context.SecurityContext context =
            org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        request.getSession().invalidate();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@CurrentUser User user) {
        if (user == null) {
            return ResponseEntity.ok(new AuthResponse(null, false));
        }
        return ResponseEntity.ok(new AuthResponse(toUserResponse(user), true));
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrfToken) {
        CsrfResponse resp = new CsrfResponse();
        resp.setToken(csrfToken.getToken());
        resp.setHeaderName(csrfToken.getHeaderName());
        resp.setParameterName(csrfToken.getParameterName());
        return ResponseEntity.ok(resp);
    }

    @PatchMapping("/users/me")
    public ResponseEntity<UserResponse> updateProfile(@CurrentUser User user, @Valid @RequestBody UpdateProfileRequest request) {
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
            userRepository.save(user);
        }
        return ResponseEntity.ok(toUserResponse(user));
    }

    @PostMapping("/users/me/password")
    public ResponseEntity<Void> changePassword(@CurrentUser User user, @Valid @RequestBody ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw ApiException.forbidden("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }

    private UserResponse toUserResponse(User user) {
        UserResponse resp = new UserResponse();
        resp.setId(user.getId());
        resp.setEmail(user.getEmail());
        resp.setDisplayName(user.getDisplayName());
        resp.setRole(user.getRole());
        resp.setCreatedAt(user.getCreatedAt());
        resp.setLastLoginAt(user.getLastLoginAt());
        return resp;
    }
}