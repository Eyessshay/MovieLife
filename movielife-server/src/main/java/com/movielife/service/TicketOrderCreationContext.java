package com.movielife.service;

import com.movielife.dto.SeatLockMetadata;
import com.movielife.entity.TicketOrder;
import lombok.Data;

import java.util.List;

/**
 * Carries the read-only validation result into the future order transaction.
 */
@Data
public class TicketOrderCreationContext {

    private Long userId;
    private Long showtimeId;
    private List<Long> seatIds;
    private String lockToken;
    private TicketOrder existingOrder;
    private SeatLockMetadata lockMetadata;

    public boolean isConsumed() {
        return existingOrder != null;
    }
}
