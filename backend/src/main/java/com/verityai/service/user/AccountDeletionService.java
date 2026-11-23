package com.verityai.service.user;

import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Removes an account and everything it owns.
 *
 * <p>This is a hard delete, not a soft one: the user asked for their data to be
 * gone. Files on disk are removed first, then the rows. Child rows (chunks,
 * summaries, citations, verifications, entities, contradictions, processing
 * events) cascade from {@code documents} / {@code conversations}, but those two
 * tables reference {@code users} with ON DELETE RESTRICT, so they are cleared
 * explicitly before the user row.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    @Value("${verityai.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Deletes the account and returns its email so the caller can drop the
     * user's sessions.
     *
     * @throws ApiException 403 when the password does not match
     */
    @Transactional
    public String deleteAccount(User user, String password) {
        if (user == null) {
            throw ApiException.unauthenticated("Authentication required");
        }
        User current = userRepository.findById(user.getId())
            .filter(candidate -> candidate.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.unauthenticated("Authentication required"));

        if (!passwordEncoder.matches(password, current.getPasswordHash())) {
            throw ApiException.forbidden("Password is incorrect");
        }

        UUID userId = current.getId();
        Path root = Paths.get(uploadDir).toAbsolutePath();
        for (String storedFilename : documentRepository.findStoredFilenamesByUserId(userId)) {
            Path file = root.resolve(storedFilename);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // Deletion must still succeed: the account is going away even if
                // one file cannot be unlinked. The path makes the leftover findable.
                log.warn("Could not delete {} during account deletion", file, e);
            }
        }

        // documents.user_id and conversations.user_id are ON DELETE RESTRICT;
        // tags, collections and reset tokens cascade from the user row itself.
        entityManager.createQuery("DELETE FROM Document d WHERE d.user.id = :userId")
            .setParameter("userId", userId)
            .executeUpdate();
        entityManager.createQuery("DELETE FROM Conversation c WHERE c.user.id = :userId")
            .setParameter("userId", userId)
            .executeUpdate();

        String email = current.getEmail();
        entityManager.createQuery("DELETE FROM User u WHERE u.id = :userId")
            .setParameter("userId", userId)
            .executeUpdate();
        log.info("Deleted account {} and all of its stored data", userId);
        return email;
    }
}
