package com.movielife.dto;

import com.movielife.enums.VenueType;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityNearbyQueryDTOTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void validQueryShouldKeepDefaultsAndAllowOptionalFilters() {
        ActivityNearbyQueryDTO dto = validQuery();
        dto.setType("drama");
        dto.setVenueType(VenueType.THEATER);

        assertThat(VALIDATOR.validate(dto)).isEmpty();
        assertThat(dto.getDistance()).isEqualTo(5000);
        assertThat(dto.getPage()).isEqualTo(1);
        assertThat(dto.getPageSize()).isEqualTo(20);
    }

    @Test
    void cityAndCoordinatesAreRequired() {
        ActivityNearbyQueryDTO dto = validQuery();
        dto.setCity("  ");
        dto.setX(null);
        dto.setY(null);

        assertThat(violatedProperties(dto)).containsExactlyInAnyOrder("city", "x", "y");
    }

    @Test
    void coordinatesShouldStayWithinEarthBounds() {
        ActivityNearbyQueryDTO dto = validQuery();
        dto.setX(180.01D);
        dto.setY(-90.01D);

        assertThat(violatedProperties(dto)).containsExactlyInAnyOrder("x", "y");
    }

    @Test
    void distanceAndPaginationShouldBeBounded() {
        ActivityNearbyQueryDTO dto = validQuery();
        dto.setDistance(99);
        dto.setPage(0);
        dto.setPageSize(101);

        assertThat(violatedProperties(dto))
                .containsExactlyInAnyOrder("distance", "page", "pageSize");
    }

    private ActivityNearbyQueryDTO validQuery() {
        ActivityNearbyQueryDTO dto = new ActivityNearbyQueryDTO();
        dto.setCity("上海");
        dto.setX(121.47D);
        dto.setY(31.23D);
        return dto;
    }

    private Set<String> violatedProperties(ActivityNearbyQueryDTO dto) {
        return VALIDATOR.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
