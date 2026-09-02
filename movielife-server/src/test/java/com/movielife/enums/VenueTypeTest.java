package com.movielife.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueTypeTest {

    @Test
    void shouldConvertDatabaseValues() {
        assertThat(VenueType.fromDbValue("cinema")).isEqualTo(VenueType.CINEMA);
        assertThat(VenueType.fromDbValue("theater")).isEqualTo(VenueType.THEATER);
    }

    @Test
    void shouldConvertApiValues() {
        assertThat(VenueType.fromApiValue("CINEMA")).isEqualTo(VenueType.CINEMA);
        assertThat(VenueType.fromApiValue("THEATER")).isEqualTo(VenueType.THEATER);
    }

    @Test
    void shouldRejectUnknownValues() {
        assertThatThrownBy(() -> VenueType.fromDbValue("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VenueType.fromApiValue("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VenueType.fromApiValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
