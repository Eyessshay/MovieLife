package com.movielife.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VenueType {

    CINEMA("cinema"),
    THEATER("theater"),
    LIVEHOUSE("livehouse"),
    EXHIBITION("exhibition");

    private final String dbValue;

    public static VenueType fromDbValue(String value) {
        if (value != null) {
            for (VenueType type : values()) {
                if (type.dbValue.equals(value)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unknown venue database type: " + value);
    }

    public static VenueType fromApiValue(String value) {
        if (value != null) {
            for (VenueType type : values()) {
                if (type.name().equals(value)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unknown venue API type: " + value);
    }
}
