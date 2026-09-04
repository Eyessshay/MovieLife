package com.movielife.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TicketStatusTest {

    @Test
    void shouldMapAllFrozenOrderAndTicketStatuses() {
        assertThat(TicketOrderStatus.fromDbValue("UNPAID")).isEqualTo(TicketOrderStatus.UNPAID);
        assertThat(TicketOrderStatus.fromDbValue("PAID")).isEqualTo(TicketOrderStatus.PAID);
        assertThat(TicketOrderStatus.fromDbValue("CANCELED")).isEqualTo(TicketOrderStatus.CANCELED);
        assertThat(TicketOrderStatus.fromDbValue("REFUNDED")).isEqualTo(TicketOrderStatus.REFUNDED);

        assertThat(TicketStatus.fromCode(0)).isEqualTo(TicketStatus.PENDING);
        assertThat(TicketStatus.fromCode(1)).isEqualTo(TicketStatus.VALID);
        assertThat(TicketStatus.fromCode(2)).isEqualTo(TicketStatus.USED);
        assertThat(TicketStatus.fromCode(3)).isEqualTo(TicketStatus.REFUNDED);
    }

    @Test
    void shouldRejectUnknownOrMissingStatusValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> TicketOrderStatus.fromDbValue("EXPIRED"));
        assertThatIllegalArgumentException().isThrownBy(() -> TicketOrderStatus.fromDbValue(null));
        assertThatIllegalArgumentException().isThrownBy(() -> TicketStatus.fromCode(4));
        assertThatIllegalArgumentException().isThrownBy(() -> TicketStatus.fromCode(null));
    }
}
