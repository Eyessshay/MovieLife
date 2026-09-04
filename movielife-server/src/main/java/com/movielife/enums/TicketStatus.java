package com.movielife.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TicketStatus {

    PENDING(0),
    VALID(1),
    USED(2),
    REFUNDED(3);

    private final int code;

    public static TicketStatus fromCode(Integer code) {
        if (code != null) {
            for (TicketStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown ticket status code: " + code);
    }
}
