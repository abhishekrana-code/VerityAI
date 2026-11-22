package com.verityai.service.knowledge;

import com.verityai.entity.KnowledgeEntity.EntityType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityNoiseFilterTest {

    @Test
    void ignoresBareYearsInAnyCenturyRange() {
        assertThat(EntityNoiseFilter.isNoise("2024", EntityType.DATE)).isTrue();
        assertThat(EntityNoiseFilter.isNoise("1999", EntityType.DATE)).isTrue();
        assertThat(EntityNoiseFilter.isNoise("20242", EntityType.METRIC)).isFalse();
    }

    @Test
    void ignoresTwoLetterAcronymsButKeepsLongerOnes() {
        assertThat(EntityNoiseFilter.isNoise("CO", EntityType.ACRONYM)).isTrue();
        assertThat(EntityNoiseFilter.isNoise("PCC", EntityType.ACRONYM)).isFalse();
        // A two-letter proper noun is a real subject, not an acronym.
        assertThat(EntityNoiseFilter.isNoise("US", EntityType.LOCATION)).isFalse();
    }

    @Test
    void ignoresGenericStructuralWords() {
        assertThat(EntityNoiseFilter.isNoise("Details", EntityType.CONCEPT)).isTrue();
        assertThat(EntityNoiseFilter.isNoise("customer", EntityType.CONCEPT)).isTrue();
        assertThat(EntityNoiseFilter.isNoise("Alpha Corp", EntityType.ORGANIZATION)).isFalse();
    }

    @Test
    void treatsBlankNamesAsNoise() {
        assertThat(EntityNoiseFilter.isNoise("  ", EntityType.OTHER)).isTrue();
        assertThat(EntityNoiseFilter.isNoise(null, EntityType.OTHER)).isTrue();
    }
}
