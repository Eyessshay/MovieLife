package com.movielife.service;

import com.movielife.dto.VenueQueryDTO;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueListItemVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@Transactional
class VenueServiceIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VenueService venueService;
    @Autowired
    private VenueMapper venueMapper;

    @Test
    void shouldApplyStatusFiltersAndPagination() {
        Venue disabledVenue = disabledVenue();
        venueMapper.insert(disabledVenue);

        PageVO<VenueListItemVO> all = venueService.queryVenues(query());
        assertThat(all.getList())
                .isNotEmpty()
                .allMatch(item -> !item.getId().equals(disabledVenue.getId()));

        VenueQueryDTO cinemaQuery = query();
        cinemaQuery.setType(VenueType.CINEMA);
        assertThat(venueService.queryVenues(cinemaQuery).getList())
                .isNotEmpty()
                .allMatch(item -> item.getType() == VenueType.CINEMA);

        VenueQueryDTO cityQuery = query();
        cityQuery.setCity("上海");
        assertThat(venueService.queryVenues(cityQuery).getList())
                .isNotEmpty()
                .allMatch(item -> "上海".equals(item.getCity()));

        VenueQueryDTO keywordQuery = query();
        keywordQuery.setKeyword("影城");
        assertThat(venueService.queryVenues(keywordQuery).getList())
                .isNotEmpty()
                .allMatch(item -> item.getName().contains("影城"));

        VenueQueryDTO pageQuery = query();
        pageQuery.setPage(2);
        pageQuery.setPageSize(1);
        PageVO<VenueListItemVO> secondPage = venueService.queryVenues(pageQuery);
        assertThat(secondPage.getList()).hasSize(1);
        assertThat(secondPage.getTotal()).isGreaterThanOrEqualTo(2L);
        assertThat(secondPage.getPage()).isEqualTo(2);
        assertThat(secondPage.getPageSize()).isEqualTo(1);
    }

    private VenueQueryDTO query() {
        return new VenueQueryDTO();
    }

    private Venue disabledVenue() {
        Venue venue = new Venue();
        venue.setName("停用测试场馆");
        venue.setType("cinema");
        venue.setCity("上海");
        venue.setAddress("测试地址");
        venue.setAvgScore(BigDecimal.ZERO);
        venue.setStatus(0);
        return venue;
    }
}
