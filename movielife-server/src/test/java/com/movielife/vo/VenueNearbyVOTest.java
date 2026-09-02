package com.movielife.vo;

import com.movielife.enums.VenueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VenueNearbyVOTest {

    @Test
    void shouldExposeOnlyNearbySummaryFields() {
        VenueNearbyVO vo = new VenueNearbyVO();
        vo.setId(1L);
        vo.setName("MovieLife影城");
        vo.setType(VenueType.CINEMA);
        vo.setCity("上海");
        vo.setAddress("徐汇区示例路1号");
        vo.setAvgScore(new BigDecimal("4.50"));
        vo.setOpenTime("10:00-22:00");
        vo.setDistance(328.5);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getName()).isEqualTo("MovieLife影城");
        assertThat(vo.getType()).isEqualTo(VenueType.CINEMA);
        assertThat(vo.getCity()).isEqualTo("上海");
        assertThat(vo.getAddress()).isEqualTo("徐汇区示例路1号");
        assertThat(vo.getAvgScore()).isEqualByComparingTo("4.50");
        assertThat(vo.getOpenTime()).isEqualTo("10:00-22:00");
        assertThat(vo.getDistance()).isEqualTo(328.5);
    }
}
