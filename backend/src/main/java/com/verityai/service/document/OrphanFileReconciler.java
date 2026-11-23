package com.verityai.service.document;

import com.verityai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removes stored PDFs that no document row references.
 *
 * <p>Orphans are left behind when the process dies between an account-deletion
 * commit and its after-commit unlink, or when a row is removed some other way.
 * The sweep is opt-in ({@code verityai.upload.reconcile.enabled=true}) because
 * silently deleting files on upgrade is not a safe default.</p>
 *
 * <p>Only regular {@code .pdf} files older than the grace period are candidates.
 * An upload writes its file before inserting the row, and a concurrent account
 * deletion can hold that insert for a moment, so a young file without a row is
 * normal and must survive.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "verityai.upload.reconcile.enabled", havingValue = "true")
public class OrphanFileReconciler {

    private final DocumentRepository documentRepository;

    @Value("${verityai.upload.dir:/var/lib/verityai/uploads}")
    private String uploadDir;

    @Value("${verityai.upload.reconcile.grace-minutes:60}")
    private long graceMinutes;

    @Scheduled(
        fixedDelayString = "${verityai.upload.reconcile.interval-ms:3600000}",
        initialDelayString = "${verityai.upload.reconcile.initial-delay-ms:60000}")
    public void reconcile() {
        Path root = Paths.get(uploadDir).toAbsolutePath();
        if (!Files.isDirectory(root)) {
            return;
        }

        Set<String> referenced;
        try {
            referenced = new HashSet<>(documentRepository.findAllStoredFilenames());
        } catch (RuntimeException e) {
            // Without the list every file looks orphaned; do nothing instead.
            log.warn("Skipping upload reconciliation: could not read stored filenames", e);
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(graceMinutes));
        int removed = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path file : entries.toList()) {
                if (isOrphan(file, referenced, cutoff) && delete(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not list upload directory {}", root, e);
        }
        if (removed > 0) {
            log.info("Removed {} orphaned upload(s) from {}", removed, root);
        }
    }

    private boolean isOrphan(Path file, Set<String> referenced, Instant cutoff) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf") || referenced.contains(name)) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(file).toInstant().isAfter(cutoff);
        } catch (IOException e) {
            log.warn("Could not read the timestamp of {}; leaving it alone", file, e);
            return false;
        }
    }

    private boolean delete(Path file) {
        try {
            long bytes = Files.size(file);
            if (Files.deleteIfExists(file)) {
                log.info("Removed orphaned upload {} ({} bytes)", file, bytes);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.warn("Could not delete orphaned upload {}", file, e);
            return false;
        }
    }
}
