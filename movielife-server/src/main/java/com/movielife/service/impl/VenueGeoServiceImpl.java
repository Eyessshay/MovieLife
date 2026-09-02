package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.constants.RedisConstants;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.VenueGeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VenueGeoServiceImpl implements VenueGeoService {

    private static final int ENABLED_STATUS = 1;

    private final VenueMapper venueMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void initializeVenueGeo() {
        List<Venue> venues = venueMapper.selectList(
                new LambdaQueryWrapper<Venue>()
                        .eq(Venue::getStatus, ENABLED_STATUS)
                        .isNotNull(Venue::getLongitude)
                        .isNotNull(Venue::getLatitude)
        );

        Map<String, List<RedisGeoCommands.GeoLocation<String>>> groupedLocations =
                new LinkedHashMap<>();
        for (Venue venue : venues) {
            VenueType venueType = VenueType.fromDbValue(venue.getType());
            String key = RedisConstants.VENUE_GEO_KEY
                    + venue.getCity() + ":" + venueType.name();
            groupedLocations.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new RedisGeoCommands.GeoLocation<>(
                            String.valueOf(venue.getId()),
                            new Point(
                                    venue.getLongitude().doubleValue(),
                                    venue.getLatitude().doubleValue()
                            )
                    ));
        }

        GeoOperations<String, String> geoOperations = stringRedisTemplate.opsForGeo();
        groupedLocations.forEach(geoOperations::add);
    }
}
