package com.verityai.controller;

import com.verityai.dto.request.DeleteAccountRequest;
import com.verityai.dto.response.AccountExportResponse;
import com.verityai.entity.User;
import com.verityai.security.CurrentUser;
import com.verityai.service.user.AccountDeletionService;
import com.verityai.service.user.AccountExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AccountExportService accountExportService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    /**
     * The user's own data as a downloadable JSON document: document metadata,
     * conversations with their messages and citations, and stored check
     * results. Reads only; nothing is changed by exporting.
     */
    @GetMapping("/me/export")
    public ResponseEntity<AccountExportResponse> exportAccount(@CurrentUser User user) {
        String filename = "verityai-export-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(accountExportService.export(user));
    }

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
