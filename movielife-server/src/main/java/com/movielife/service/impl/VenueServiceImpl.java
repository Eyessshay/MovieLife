package com.movielife.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueDetailCacheDTO;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.dto.VenueQueryDTO;
import com.movielife.entity.Hall;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.VenueService;
import com.movielife.vo.HallVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueListItemVO;
import com.movielife.vo.VenueNearbyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {

    private static final int ENABLED_STATUS = 1;
    private static final String MOVIE_BIZ_TYPE = "MOVIE";
    private static final int MOVIE_CANDIDATE_MULTIPLIER = 5;
    private static final int MAX_MOVIE_CANDIDATES = 200;

    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;
    private final MovieMapper movieMapper;
    private final ShowtimeMapper showtimeMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO) {
        LambdaQueryWrapper<Venue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Venue::getStatus, ENABLED_STATUS)
                .eq(StrUtil.isNotBlank(queryDTO.getCity()), Venue::getCity, queryDTO.getCity())
                .eq(queryDTO.getType() != null, Venue::getType,
                        queryDTO.getType() == null ? null : queryDTO.getType().getDbValue())
                .like(StrUtil.isNotBlank(queryDTO.getKeyword()), Venue::getName, queryDTO.getKeyword())
                .orderByDesc(Venue::getId);

        Page<Venue> venuePage = venueMapper.selectPage(
                new Page<>(queryDTO.getPage(), queryDTO.getPageSize()),
                wrapper
        );
        List<VenueListItemVO> list = venuePage.getRecords().stream()
                .map(this::toListVO)
                .collect(Collectors.toList());

        return new PageVO<>(list, venuePage.getTotal(), queryDTO.getPage(), queryDTO.getPageSize());
    }

    @Override
    public VenueDetailVO queryVenueDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        return toDetailVO(findPublicVenueDetail(id));
    }

    @Override
    public List<HallVO> queryVenueHalls(Long venueId) {
        if (venueId == null || venueId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        Venue venue = venueMapper.selectById(venueId);
        if (venue == null || !Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())) {
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }

        return hallMapper.selectList(
                        new LambdaQueryWrapper<Hall>()
                                .eq(Hall::getVenueId, venueId)
                                .orderByAsc(Hall::getId)
                ).stream()
                .map(this::toHallVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<VenueNearbyVO> queryNearbyVenues(VenueNearbyQueryDTO queryDTO) {
        boolean movieFilterEnabled = validateMovieFilter(queryDTO);
        if (movieFilterEnabled && movieMapper.selectById(queryDTO.getBizId()) == null) {
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }

        String key = RedisConstants.VENUE_GEO_KEY
                + queryDTO.getCity() + ":" + queryDTO.getType().name();
        long from = (long) (queryDTO.getPage() - 1) * queryDTO.getPageSize();
        long end = (long) queryDTO.getPage() * queryDTO.getPageSize();
        long candidateLimit = movieFilterEnabled
                ? Math.min(end * MOVIE_CANDIDATE_MULTIPLIER, MAX_MOVIE_CANDIDATES)
                : end;

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults;
        try {
            RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                    .newGeoRadiusArgs()
                    .includeDistance()
                    .sortAscending()
                    .limit(candidateLimit);
            geoResults = stringRedisTemplate.opsForGeo().radius(
                    key,
                    new Circle(
                            new Point(queryDTO.getX(), queryDTO.getY()),
                            new Distance(queryDTO.getDistance() / 1000D, Metrics.KILOMETERS)
                    ),
                    args
            );
        } catch (RuntimeException exception) {
            log.error("Redis GEO nearby query failed, key={}", key, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            return Collections.emptyList();
        }

        if (movieFilterEnabled) {
            return queryMovieFilteredNearbyVenues(
                    geoResults.getContent(),
                    queryDTO.getBizId(),
                    from,
                    queryDTO.getPageSize()
            );
        }

        if (geoResults.getContent().size() <= from) {
            return Collections.emptyList();
        }
        int toIndex = (int) Math.min(end, geoResults.getContent().size());
        return assembleNearbyVenues(
                geoResults.getContent().subList((int) from, toIndex),
                null
        );
    }

    private List<VenueNearbyVO> queryMovieFilteredNearbyVenues(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoCandidates,
            Long movieId,
            long from,
            int pageSize) {
        List<Long> candidateIds = extractVenueIds(geoCandidates);
        Map<Long, Venue> venueMap = loadEnabledVenues(candidateIds);
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> enabledCandidates = geoCandidates.stream()
                .filter(result -> venueMap.containsKey(parseVenueId(result)))
                .collect(Collectors.toList());
        if (enabledCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> enabledVenueIds = extractVenueIds(enabledCandidates);
        Set<Long> validVenueIds = showtimeMapper
                .selectVenueIdsWithFutureMovieShowtimes(movieId, enabledVenueIds)
                .stream()
                .collect(Collectors.toSet());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> filteredCandidates = enabledCandidates.stream()
                .filter(result -> validVenueIds.contains(parseVenueId(result)))
                .collect(Collectors.toList());
        if (filteredCandidates.size() <= from) {
            return Collections.emptyList();
        }

        int toIndex = (int) Math.min(from + pageSize, filteredCandidates.size());
        return assembleNearbyVenues(
                filteredCandidates.subList((int) from, toIndex),
                venueMap
        );
    }

    private List<VenueNearbyVO> assembleNearbyVenues(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageResults,
            Map<Long, Venue> loadedVenueMap) {
        List<Long> venueIds = pageResults.stream()
                .map(this::parseVenueId)
                .collect(Collectors.toList());
        Map<Long, Venue> venueMap = loadedVenueMap == null
                ? loadEnabledVenues(venueIds)
                : loadedVenueMap;

        List<VenueNearbyVO> result = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult : pageResults) {
            Long venueId = parseVenueId(geoResult);
            Venue venue = venueMap.get(venueId);
            if (venue != null) {
                double distanceInMeters = geoResult.getDistance()
                        .in(Metrics.KILOMETERS)
                        .getValue() * 1000D;
                result.add(toNearbyVO(venue, distanceInMeters));
            }
        }
        return result;
    }

    private Map<Long, Venue> loadEnabledVenues(List<Long> venueIds) {
        return venueMapper.selectList(
                        new LambdaQueryWrapper<Venue>()
                                .in(Venue::getId, venueIds)
                                .eq(Venue::getStatus, ENABLED_STATUS)
                ).stream()
                .collect(Collectors.toMap(
                        Venue::getId,
                        venue -> venue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private List<Long> extractVenueIds(
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults) {
        return geoResults.stream()
                .map(this::parseVenueId)
                .collect(Collectors.toList());
    }

    private Long parseVenueId(GeoResult<RedisGeoCommands.GeoLocation<String>> result) {
        return Long.valueOf(result.getContent().getName());
    }

    private boolean validateMovieFilter(VenueNearbyQueryDTO queryDTO) {
        boolean hasBizType = StrUtil.isNotBlank(queryDTO.getBizType());
        boolean hasBizId = queryDTO.getBizId() != null;
        if (hasBizType != hasBizId) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (!hasBizType) {
            return false;
        }
        if (!MOVIE_BIZ_TYPE.equals(queryDTO.getBizType())) {
            throw new BusinessException(ErrorCode.INVALID_BIZ_TYPE);
        }
        return true;
    }

    private VenueDetailCacheDTO findPublicVenueDetail(Long id) {
        String key = RedisConstants.VENUE_DETAIL_KEY + id;
        String json = readCache(key);
        if (json != null) {
            if (json.isEmpty()) {
                throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
            }
            try {
                return objectMapper.readValue(json, VenueDetailCacheDTO.class);
            } catch (JsonProcessingException exception) {
                log.warn("Invalid venue detail cache, rebuilding key={}", key, exception);
                deleteCache(key);
            }
        }

        Venue venue = venueMapper.selectById(id);
        if (venue == null || !Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())) {
            writeCache(key, "", RedisConstants.VENUE_DETAIL_NULL_TTL);
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }

        VenueDetailCacheDTO cacheDTO = toCacheDTO(venue);
        try {
            long randomMinutes = ThreadLocalRandom.current().nextLong(
                    RedisConstants.VENUE_DETAIL_TTL_RANDOM_MINUTES + 1
            );
            Duration ttl = RedisConstants.VENUE_DETAIL_TTL.plusMinutes(randomMinutes);
            writeCache(key, objectMapper.writeValueAsString(cacheDTO), ttl);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize venue detail, venueId={}", id, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return cacheDTO;
    }

    private String readCache(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn("Redis read failed, falling back to MySQL, key={}", key, exception);
            return null;
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException exception) {
            log.warn("Redis write failed, response will continue without cache, key={}", key, exception);
        }
    }

    private void deleteCache(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Failed to delete invalid cache, key={}", key, exception);
        }
    }

    private VenueListItemVO toListVO(Venue venue) {
        VenueListItemVO vo = new VenueListItemVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setAvgScore(venue.getAvgScore());
        vo.setOpenTime(venue.getOpenTime());
        return vo;
    }

    private VenueDetailCacheDTO toCacheDTO(Venue venue) {
        VenueDetailCacheDTO dto = new VenueDetailCacheDTO();
        dto.setId(venue.getId());
        dto.setName(venue.getName());
        dto.setType(venue.getType());
        dto.setCity(venue.getCity());
        dto.setAddress(venue.getAddress());
        dto.setLongitude(venue.getLongitude());
        dto.setLatitude(venue.getLatitude());
        dto.setPhone(venue.getPhone());
        dto.setOpenTime(venue.getOpenTime());
        dto.setAvgScore(venue.getAvgScore());
        return dto;
    }

    private VenueDetailVO toDetailVO(VenueDetailCacheDTO venue) {
        VenueDetailVO vo = new VenueDetailVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setLongitude(venue.getLongitude());
        vo.setLatitude(venue.getLatitude());
        vo.setPhone(venue.getPhone());
        vo.setOpenTime(venue.getOpenTime());
        vo.setAvgScore(venue.getAvgScore());
        return vo;
    }

    private HallVO toHallVO(Hall hall) {
        HallVO vo = new HallVO();
        vo.setId(hall.getId());
        vo.setVenueId(hall.getVenueId());
        vo.setName(hall.getName());
        return vo;
    }

    private VenueNearbyVO toNearbyVO(Venue venue, double distance) {
        VenueNearbyVO vo = new VenueNearbyVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setAvgScore(venue.getAvgScore());
        vo.setOpenTime(venue.getOpenTime());
        vo.setDistance(distance);
        return vo;
    }
}
