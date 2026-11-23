package com.verityai.service.document;

import com.verityai.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    }

    private Path write(String name, Instant modified) throws IOException {
        Path file = uploadDir.resolve(name);
        Files.writeString(file, "%PDF-1.7");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void removesOnlyAgedUnreferencedPdfs() throws IOException {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        Path referenced = write("referenced.pdf", old);
        Path orphan = write("orphan.pdf", old);
        Path inFlight = write("in-flight.pdf", Instant.now());
        Path notAPdf = write("notes.txt", old);

        when(documentRepository.findAllStoredFilenames()).thenReturn(List.of("referenced.pdf"));

        reconciler.reconcile();

        assertThat(orphan).doesNotExist();
        assertThat(referenced).exists();
        assertThat(inFlight).as("an upload may write its file before inserting the row").exists();
        assertThat(notAPdf).as("the sweep only owns stored PDFs").exists();
    }

    @Test
    void doesNothingWhenTheReferencedFilenamesCannotBeRead() throws IOException {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        Path orphan = write("orphan.pdf", old);
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
