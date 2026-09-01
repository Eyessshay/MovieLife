package com.movielife.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MovieStatus {

    COMING(0),
    SHOWING(1),
    OFFLINE(2);

    private final int code;

    public static MovieStatus fromCode(Integer code) {
        if (code != null) {
            for (MovieStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown movie status code: " + code);
    }
}
