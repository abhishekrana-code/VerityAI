package com.verityai.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of one contradiction detection run.
 *
 * <p>Counts are honest: {@code analyzedDocuments}/{@code analyzedClaims} are the
 * bounded inputs, {@code candidatePairs} the deterministic pairs sent to the
 * model, and {@code found}/{@code stored} the pairs the classifier accepted.
 * A failed run stores nothing and leaves earlier findings untouched.</p>
 */
@Data
public class ContradictionDetectionResponse {

    /** COMPLETED | FAILED | SKIPPED */
    private String status;
    /** DOCUMENT | LIBRARY */
    private String scope;
    private List<UUID> analyzedDocumentIds;
    private int analyzedDocuments;
    private int analyzedClaims;
    private int candidatePairs;
    private int found;
    private int stored;
    private String model;
    private Instant detectedAt;
    private String error;
}
