package com.verityai.controller;

import com.verityai.dto.request.DeleteAccountRequest;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.user.AccountDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account-level operations that are not part of signing in.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AccountController {

    private final AccountDeletionService accountDeletionService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    /**
     * Deletes the signed-in account and every row and file it owns. The password
     * is required again, and all of the user's sessions are dropped so no
     * browser stays signed in to an account that no longer exists.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(@CurrentUser User user,
                                              @Valid @RequestBody DeleteAccountRequest request,
                                              HttpServletRequest httpRequest) {
        String email = accountDeletionService.deleteAccount(user, request.getPassword());

        sessionRepository.findByPrincipalName(email).keySet().forEach(sessionRepository::deleteById);
        SecurityContextHolder.clearContext();
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // The session store dropped it above; nothing left to do.
            }
        }
        return ResponseEntity.noContent().build();
    }
}
