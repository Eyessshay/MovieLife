package com.movielife.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TicketOrderStatus {

    UNPAID("UNPAID"),
    PAID("PAID"),
    CANCELED("CANCELED"),
    REFUNDED("REFUNDED");

    private final String dbValue;

    public static TicketOrderStatus fromDbValue(String value) {
        if (value != null) {
            for (TicketOrderStatus status : values()) {
                if (status.dbValue.equals(value)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown ticket order status: " + value);
    }
}
