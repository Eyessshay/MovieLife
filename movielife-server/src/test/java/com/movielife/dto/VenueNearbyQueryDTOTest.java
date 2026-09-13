package com.movielife.dto;

import com.movielife.enums.VenueType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.support.DefaultConversionService;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueNearbyQueryDTOTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldProvideExpectedDefaults() {
        VenueNearbyQueryDTO dto = new VenueNearbyQueryDTO();

        assertThat(dto.getDistance()).isEqualTo(5000);
        assertThat(dto.getPage()).isEqualTo(1);
        assertThat(dto.getPageSize()).isEqualTo(10);
        assertThat(dto.getBizType()).isNull();
        assertThat(dto.getBizId()).isNull();
    }

    @Test
    void shouldBindVenueTypeFromApiValue() {
        DefaultConversionService conversionService = new DefaultConversionService();

        assertThat(conversionService.convert("CINEMA", VenueType.class))
                .isEqualTo(VenueType.CINEMA);
        assertThatThrownBy(() -> conversionService.convert("ABC", VenueType.class))
                .isInstanceOf(ConversionFailedException.class);
    }

    @Test
    void shouldValidatePaginationCoordinatesAndDistance() {
        VenueNearbyQueryDTO dto = validDto();
        dto.setPage(0);
        dto.setPageSize(101);
        dto.setX(180.1);
        dto.setY(-90.1);
        dto.setDistance(99);

        assertThat(violatedProperties(dto))
                .contains("page", "pageSize", "x", "y", "distance");

        dto.setPage(1);
        dto.setPageSize(100);
        dto.setX(-180.0);
        dto.setY(90.0);
        dto.setDistance(50000);
        assertThat(VALIDATOR.validate(dto)).isEmpty();
    }

    @Test
    void shouldRequireBusinessFilterPairAndSupportedType() {
        VenueNearbyQueryDTO dto = validDto();
        dto.setBizType("MOVIE");
        assertThat(violatedProperties(dto)).contains("bizFilterPairValid");

        dto.setBizId(1L);
        assertThat(VALIDATOR.validate(dto)).isEmpty();

        dto.setBizType("ACTIVITY");
        assertThat(VALIDATOR.validate(dto)).isEmpty();

        dto.setBizType("EVENT");
        assertThat(violatedProperties(dto)).contains("bizTypeValid");

        dto.setBizType(null);
        assertThat(violatedProperties(dto)).contains("bizFilterPairValid");
    }

    @Test
    void shouldRequireCoreNearbyParameters() {
        VenueNearbyQueryDTO dto = new VenueNearbyQueryDTO();

        assertThat(violatedProperties(dto)).contains("city", "type", "x", "y");
    }

    private VenueNearbyQueryDTO validDto() {
        VenueNearbyQueryDTO dto = new VenueNearbyQueryDTO();
        dto.setCity("上海");
        dto.setType(VenueType.CINEMA);
        dto.setX(121.437);
        dto.setY(31.188);
        return dto;
    }

    private Set<String> violatedProperties(VenueNearbyQueryDTO dto) {
        return VALIDATOR.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
