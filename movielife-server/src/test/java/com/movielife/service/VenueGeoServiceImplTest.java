package com.movielife.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.movielife.entity.Venue;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.impl.VenueGeoServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueGeoServiceImplTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("VenueGeoServiceImplTest");
        TableInfoHelper.initTableInfo(assistant, Venue.class);
    }

    @Mock
    private VenueMapper venueMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private GeoOperations<String, String> geoOperations;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldGroupLocationsAndBatchWriteEachGeoKey() {
        when(venueMapper.selectList(any())).thenReturn(Arrays.asList(
                venue(1L, "cinema", "上海", "121.437000", "31.188000"),
                venue(3L, "cinema", "上海", "121.438000", "31.189000"),
                venue(2L, "theater", "上海", "121.452000", "31.229000")
        ));
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        VenueGeoService service = new VenueGeoServiceImpl(venueMapper, stringRedisTemplate);

        service.initializeVenueGeo();

        ArgumentCaptor<Wrapper<Venue>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(venueMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("status", "longitude", "latitude", "IS NOT NULL");

        ArgumentCaptor<Iterable<RedisGeoCommands.GeoLocation<String>>> cinemaCaptor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(geoOperations).add(eq("venue:geo:上海:CINEMA"), cinemaCaptor.capture());
        List<RedisGeoCommands.GeoLocation<String>> cinemaLocations =
                (List<RedisGeoCommands.GeoLocation<String>>) cinemaCaptor.getValue();
        assertThat(cinemaLocations).hasSize(2);
        assertThat(cinemaLocations.get(0).getName()).isEqualTo("1");
        assertThat(cinemaLocations.get(0).getPoint().getX()).isEqualTo(121.437000);
        assertThat(cinemaLocations.get(0).getPoint().getY()).isEqualTo(31.188000);

        ArgumentCaptor<Iterable<RedisGeoCommands.GeoLocation<String>>> theaterCaptor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(geoOperations).add(eq("venue:geo:上海:THEATER"), theaterCaptor.capture());
        assertThat((List<?>) theaterCaptor.getValue()).hasSize(1);
    }

    private Venue venue(Long id, String type, String city, String longitude, String latitude) {
        Venue venue = new Venue();
        venue.setId(id);
        venue.setType(type);
        venue.setCity(city);
        venue.setLongitude(new BigDecimal(longitude));
        venue.setLatitude(new BigDecimal(latitude));
        return venue;
    }
}
