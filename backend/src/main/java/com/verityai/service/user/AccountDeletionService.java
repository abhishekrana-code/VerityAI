package com.verityai.service.user;

import com.verityai.entity.User;
import com.verityai.exception.ApiException;
import com.verityai.repository.DocumentRepository;
import com.verityai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * Removes an account and everything it owns.
 *
 * <p>This is a hard delete: the user asked for their data to be gone. One
 * {@code DELETE FROM users} removes the account, its documents, conversations,
 * tags, collections and every child row through them (cascades in V1–V10).</p>
 *
 * <p>The cascade alone keeps the <em>rows</em> consistent: an upload that
 * commits before the delete is cascaded away with the account, and one that
 * arrives afterwards fails its own foreign key. It cannot keep the <em>file
 * list</em> complete, though — a document inserted between reading the
 * filenames and deleting the user would disappear with its PDF still on disk.
 * So the user row is locked first: an upload's foreign-key check needs a KEY
 * SHARE lock on that row, waits here, and then fails cleanly (its own rollback
 * removes the file it wrote).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${verityai.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Deletes the account and returns its email so the caller can drop the
     * remaining sessions.
     *
     * @throws ApiException 403 when the password does not match
     */
    @Transactional
    public String deleteAccount(User user, String password) {
        if (user == null) {
            throw ApiException.unauthenticated("Authentication required");
        }
        UUID userId = user.getId();
        User current = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> ApiException.unauthenticated("Authentication required"));

        if (!passwordEncoder.matches(password, current.getPasswordHash())) {
            throw ApiException.forbidden("Password is incorrect");
        }

        List<String> storedFiles = documentRepository.findStoredFilenamesByUserId(userId);
        String email = current.getEmail();
        userRepository.delete(current);

        // Rows are gone at commit; unlink the files only then, so a rollback
        // never leaves rows pointing at missing PDFs.
        Path root = Paths.get(uploadDir).toAbsolutePath();
        afterCommit(() -> storedFiles.forEach(name -> deleteQuietly(root.resolve(name))));

        log.info("Deleted account {} and all of its stored data", userId);
        return email;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The account is gone either way; the path is the only way to find
            // a leftover file afterwards.
            log.warn("Could not delete {} during account deletion", file, e);
        }
    }
}
