package com.verityai.controller;

import com.verityai.dto.request.ChangePasswordRequest;
import com.verityai.dto.request.LoginRequest;
import com.verityai.dto.request.PasswordResetConfirmRequest;
import com.verityai.dto.request.PasswordResetRequest;
import com.verityai.dto.request.RegisterRequest;
import com.verityai.dto.request.UpdateProfileRequest;
import com.verityai.dto.response.AuthResponse;
import com.verityai.dto.response.CsrfResponse;
import com.verityai.dto.response.UserResponse;
import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.UserRepository;
import com.verityai.security.CurrentUser;
import com.verityai.security.RateLimitService;
import com.verityai.service.auth.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final RateLimitService rateLimitService;
    private final PasswordResetService passwordResetService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    /**
     * Creates the account but never establishes a session and never reveals
     * whether the email was already registered. Both branches return the same
     * body; the bcrypt hash is computed in both so response timing does not
     * leak account existence either.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        rateLimitService.checkRegister(clientIp(httpRequest));

        String encoded = passwordEncoder.encode(request.getPassword());
        if (!userRepository.existsByEmailIgnoreCaseAndNotDeleted(request.getEmail())) {
            User user = new User();
            user.setEmail(request.getEmail());
            user.setPasswordHash(encoded);
            user.setDisplayName(request.getDisplayName());
            user.setRole("USER");
            try {
                userRepository.save(user);
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent registration (or a soft-deleted
                // account still owns the unique email). Stay generic.
                log.debug("Registration for an existing email raced; returning generic response");
            }
        }

        return ResponseEntity.ok(new AuthResponse(null, false));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        rateLimitService.checkLogin(clientIp(httpRequest));
        // Per-account cap as well: limits credential stuffing of one account
        // from many IPs (same window/limit as the IP check).
        rateLimitService.checkLoginAccount(request.getEmail());
        authenticateAndSave(request.getEmail(), request.getPassword(), httpRequest, httpResponse);

        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
            .orElseThrow(() -> ApiException.unauthenticated("Invalid credentials"));

        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(toUserResponse(user), true));
    }

    /**
     * Authenticates, rotates the session id (fixation defence), and invalidates
     * every other session for the same account (single-session policy).
     *
     * <p>Session rows are deleted from the Spring Session JDBC store rather than
     * relying on the in-memory {@code SessionRegistry}: the store survives
     * restarts and is the source of truth for whether a cookie is still valid.
     * An old browser's next request therefore has no authenticated context and
     * receives the normal JSON 401.
     */
    private void authenticateAndSave(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, password)
        );

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            // Spring Session JDBC persists the id change itself: on save it
            // updates the existing row (old id -> new id) in place. Deleting
            // the old row here would make that update affect zero rows and
            // leave the client holding a cookie with no backing session.
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        String currentSessionId = session.getId();

        invalidateOtherSessions(auth.getName(), currentSessionId);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private void invalidateOtherSessions(String principalName, String currentSessionId) {
        try {
            sessionRepository.findByPrincipalName(principalName).keySet().stream()
                .filter(id -> !id.equals(currentSessionId))
                .forEach(this::deleteSessionQuietly);
        } catch (Exception e) {
            // Never fail a successful login because session cleanup failed.
            log.warn("Could not invalidate other sessions for {}", principalName, e);
        }
    }

    private void deleteSessionQuietly(String sessionId) {
        try {
            sessionRepository.deleteById(sessionId);
        } catch (Exception e) {
            log.debug("Could not delete session {}", sessionId, e);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Always returns 200, whether or not the email exists. The token is only
     * created and mailed for real accounts.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                                     HttpServletRequest httpRequest) {
        rateLimitService.checkPasswordReset(clientIp(httpRequest));
        rateLimitService.checkPasswordResetAccount(request.getEmail());
        passwordResetService.request(
            request.getEmail(),
            httpRequest.getHeader("Origin"),
            httpRequest.getHeader("Referer"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@CurrentUser User user) {
        if (user == null) {
            return ResponseEntity.ok(new AuthResponse(null, false));
        }
        // The principal stored in the session is a serialized snapshot. Reload
        // so profile changes made in this or another request are reflected
        // immediately instead of after the next sign-in.
        User current = userRepository.findById(user.getId()).orElse(null);
        if (current == null || current.getDeletedAt() != null) {
            return ResponseEntity.ok(new AuthResponse(null, false));
        }
        return ResponseEntity.ok(new AuthResponse(toUserResponse(current), true));
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
    public ResponseEntity<UserResponse> updateProfile(@CurrentUser User user,
                                                      @Valid @RequestBody UpdateProfileRequest request,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        // Never save the session principal directly: it is a snapshot taken at
        // sign-in, so merging it back would overwrite fields changed since then.
        User current = requireCurrentUser(user);
        if (request.getDisplayName() != null) {
            current.setDisplayName(request.getDisplayName());
            current = userRepository.save(current);
        }
        refreshSessionPrincipal(current, httpRequest, httpResponse);
        return ResponseEntity.ok(toUserResponse(current));
    }

    @PostMapping("/users/me/password")
    public ResponseEntity<Void> changePassword(@CurrentUser User user, @Valid @RequestBody ChangePasswordRequest request) {
        User current = requireCurrentUser(user);
        if (!passwordEncoder.matches(request.getCurrentPassword(), current.getPasswordHash())) {
            throw ApiException.forbidden("Current password is incorrect");
        }
        current.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(current);
        return ResponseEntity.ok().build();
    }

    private User requireCurrentUser(User user) {
        if (user == null) {
            throw ApiException.unauthenticated("Authentication required");
        }
        return userRepository.findById(user.getId())
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.unauthenticated("Authentication required"));
    }

    /**
     * Replaces the serialized principal in the session with the fresh entity so
     * later requests in the same session see the update without re-authenticating.
     */
    private void refreshSessionPrincipal(User fresh, HttpServletRequest request, HttpServletResponse response) {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null) {
            return;
        }
        Authentication refreshed = new UsernamePasswordAuthenticationToken(
            fresh, existing.getCredentials(), fresh.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        return Objects.toString(request.getRemoteAddr(), "unknown");
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
