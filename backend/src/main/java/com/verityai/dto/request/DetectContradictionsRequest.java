package com.verityai.dto.request;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Requests a contradiction detection run.
 *
 * <p>When {@code documentIds} is present the run is scoped to those documents
 * (each is compared against the user's other ready documents). When it is
 * absent the run covers the user's ready library, bounded by server config.</p>
 */
@Data
public class DetectContradictionsRequest {

    private List<UUID> documentIds;
}
