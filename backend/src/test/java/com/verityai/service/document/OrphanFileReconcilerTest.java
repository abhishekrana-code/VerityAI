package com.verityai.service.document;

import com.verityai.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrphanFileReconcilerTest {

    @Mock private DocumentRepository documentRepository;

    @TempDir
    Path uploadDir;

    private OrphanFileReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new OrphanFileReconciler(documentRepository);
        ReflectionTestUtils.setField(reconciler, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(reconciler, "graceMinutes", 60L);
        ReflectionTestUtils.setField(reconciler, "dryRun", false);
    }

    private Path write(String name, Instant modified) throws IOException {
        Path file = uploadDir.resolve(name);
        Files.writeString(file, "%PDF-1.7");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void removesOnlyAgedUnreferencedFilesTheAppWrote() throws IOException {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        String referencedName = UUID.randomUUID() + ".pdf";
        Path referenced = write(referencedName, old);
        Path orphan = write(UUID.randomUUID() + ".pdf", old);
        Path inFlight = write(UUID.randomUUID() + ".pdf", Instant.now());
        Path notStoredByUs = write("operator-copy.pdf", old);

        when(documentRepository.findAllStoredFilenames()).thenReturn(List.of(referencedName));

        reconciler.reconcile();

        assertThat(orphan).doesNotExist();
        assertThat(referenced).exists();
        assertThat(inFlight).as("an upload may write its file before inserting the row").exists();
        assertThat(notStoredByUs).as("a PDF this app did not write").exists();
    }

    @Test
    void dryRunLogsTheFutureRemovalAndKeepsTheFile(CapturedOutput output) throws IOException {
        ReflectionTestUtils.setField(reconciler, "dryRun", true);
        String orphanName = UUID.randomUUID() + ".pdf";
        Path orphan = write(orphanName, Instant.now().minus(2, ChronoUnit.HOURS));
        when(documentRepository.findAllStoredFilenames()).thenReturn(List.of());

        reconciler.reconcile();

        assertThat(orphan).exists();
        assertThat(output)
            .contains("Dry run: would remove orphaned upload")
            .contains(orphanName)
            .contains("Would remove 1 orphaned upload(s)");
    }

    @Test
    void doesNothingWhenTheReferencedFilenamesCannotBeRead() throws IOException {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        Path orphan = write(UUID.randomUUID() + ".pdf", old);
        when(documentRepository.findAllStoredFilenames())
            .thenThrow(new IllegalStateException("database unavailable"));

        reconciler.reconcile();

        assertThat(orphan).as("every file would look orphaned without the list").exists();
    }

    @Test
    void ignoresAMissingUploadDirectory() {
        ReflectionTestUtils.setField(reconciler, "uploadDir", uploadDir.resolve("absent").toString());

        reconciler.reconcile();

        assertThat(uploadDir.resolve("absent")).doesNotExist();
    }
}
