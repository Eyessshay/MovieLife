package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.ActivityNearbyQueryDTO;
import com.movielife.dto.ActivityQueryDTO;
import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.ActivityService;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityNearbyVO;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private static final String ACTIVITY_BIZ_TYPE = "ACTIVITY";
    private static final int ACTIVE_STATUS = 1;
    private static final int NEARBY_CANDIDATE_MULTIPLIER = 5;
    private static final int MAX_NEARBY_CANDIDATES = 200;

    private final ActivityMapper activityMapper;
    private final ShowtimeMapper showtimeMapper;
    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public PageVO<ActivityListItemVO> queryActivities(ActivityQueryDTO query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStatus, ACTIVE_STATUS)
                .eq(StringUtils.hasText(query.getCity()), Activity::getCity, trim(query.getCity()))
                .eq(StringUtils.hasText(query.getType()), Activity::getType, trim(query.getType()))
                .like(StringUtils.hasText(query.getKeyword()), Activity::getTitle, trim(query.getKeyword()))
                .orderByDesc(Activity::getCreateTime)
                .orderByDesc(Activity::getId);

        Page<Activity> page = activityMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<ActivityListItemVO> items = page.getRecords().stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
        return new PageVO<>(items, page.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public List<ActivityNearbyVO> queryNearbyActivities(ActivityNearbyQueryDTO query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        String city = trim(query.getCity());
        String activityType = trim(query.getType());
        long candidateLimit = Math.min(
                (long) query.getPage() * query.getPageSize() * NEARBY_CANDIDATE_MULTIPLIER,
                MAX_NEARBY_CANDIDATES
        );
        List<VenueType> venueTypes = query.getVenueType() == null
                ? java.util.Arrays.asList(VenueType.values())
                : Collections.singletonList(query.getVenueType());

        List<NearbyVenueCandidate> geoCandidates = queryGeoCandidates(
                query, city, venueTypes, candidateLimit);
        if (geoCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Venue> venueMap = indexVenues(venueMapper.selectBatchIds(
                geoCandidates.stream().map(NearbyVenueCandidate::getVenueId)
                        .collect(Collectors.toList())));
        List<NearbyVenueCandidate> validCandidates = geoCandidates.stream()
                .filter(candidate -> isValidNearbyVenue(
                        candidate, venueMap.get(candidate.getVenueId()), city))
                .collect(Collectors.toList());
        validCandidates.sort(Comparator.comparingDouble(NearbyVenueCandidate::getDistance)
                .thenComparing(NearbyVenueCandidate::getVenueId));
        if (validCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> validVenueIds = validCandidates.stream()
                .map(NearbyVenueCandidate::getVenueId)
                .collect(Collectors.toList());
        List<Showtime> showtimes = showtimeMapper
                .selectFutureActivityShowtimesByVenueIds(validVenueIds);
        if (showtimes.isEmpty()) {
            return Collections.emptyList();
        }
        showtimes.forEach(this::validateNearbyShowtime);

        Map<Long, NearbyVenueCandidate> candidateMap = validCandidates.stream()
                .collect(Collectors.toMap(
                        NearbyVenueCandidate::getVenueId,
                        candidate -> candidate,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Set<Long> activityIds = showtimes.stream()
                .map(Showtime::getBizId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> hallIds = showtimes.stream()
                .map(Showtime::getHallId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Activity> activityMap = indexActivities(activityMapper.selectBatchIds(activityIds));
        Map<Long, Hall> hallMap = indexHalls(hallMapper.selectBatchIds(hallIds));

        Map<Long, NearbyActivityChoice> choices = new HashMap<>();
        for (Showtime showtime : showtimes) {
            NearbyVenueCandidate candidate = candidateMap.get(showtime.getVenueId());
            Venue venue = venueMap.get(showtime.getVenueId());
            Activity activity = activityMap.get(showtime.getBizId());
            Hall hall = hallMap.get(showtime.getHallId());
            if (candidate == null || venue == null || activity == null) {
                throw internalError("missing nearby activity relation, showtimeId=" + showtime.getId());
            }
            validateShowtimeRelation(showtime, venue, hall);
            if (!Integer.valueOf(ACTIVE_STATUS).equals(activity.getStatus())
                    || (StringUtils.hasText(activityType)
                    && !activityType.equals(activity.getType()))) {
                continue;
            }

            NearbyActivityChoice choice = new NearbyActivityChoice(
                    activity, venue, showtime, candidate.getDistance());
            NearbyActivityChoice current = choices.get(activity.getId());
            if (current == null || compareChoice(choice, current) < 0) {
                choices.put(activity.getId(), choice);
            }
        }

        List<NearbyActivityChoice> sorted = new ArrayList<>(choices.values());
        sorted.sort(Comparator
                .comparingDouble(NearbyActivityChoice::getDistance)
                .thenComparing(choice -> choice.getShowtime().getStartTime())
                .thenComparing(choice -> choice.getActivity().getId()));

        long from = (long) (query.getPage() - 1) * query.getPageSize();
        if (sorted.size() <= from) {
            return Collections.emptyList();
        }
        int toIndex = (int) Math.min(from + query.getPageSize(), sorted.size());
        return sorted.subList((int) from, toIndex).stream()
                .map(this::toNearbyVO)
                .collect(Collectors.toList());
    }

    @Override
    public ActivityDetailVO queryActivityDetail(Long id) {
        Activity activity = requireActiveActivity(id);
        ActivityDetailVO result = new ActivityDetailVO();
        copyDiscoveryFields(activity, result);
        result.setDescription(activity.getDescription());
        return result;
    }

    @Override
    public List<ActivityShowtimeVO> queryActivityShowtimes(
            Long activityId, ActivityShowtimeQueryDTO query) {
        requireActiveActivity(activityId);
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Showtime> wrapper = new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getBizType, ACTIVITY_BIZ_TYPE)
                .eq(Showtime::getBizId, activityId)
                .eq(Showtime::getStatus, ACTIVE_STATUS)
                .gt(Showtime::getStartTime, now)
                .eq(query.getVenueId() != null, Showtime::getVenueId, query.getVenueId())
                .orderByAsc(Showtime::getStartTime)
                .orderByAsc(Showtime::getId);
        if (query.getDate() != null) {
            wrapper.ge(Showtime::getStartTime, query.getDate().atStartOfDay())
                    .lt(Showtime::getStartTime, query.getDate().plusDays(1).atStartOfDay());
        }

        List<Showtime> showtimes = showtimeMapper.selectList(wrapper);
        if (showtimes.isEmpty()) {
            return Collections.emptyList();
        }

        for (Showtime showtime : showtimes) {
            validateShowtimeCore(showtime);
        }

        Set<Long> venueIds = showtimes.stream()
                .map(Showtime::getVenueId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> hallIds = showtimes.stream()
                .map(Showtime::getHallId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Venue> venues = indexVenues(venueMapper.selectBatchIds(venueIds));
        Map<Long, Hall> halls = indexHalls(hallMapper.selectBatchIds(hallIds));

        List<ActivityShowtimeVO> result = new ArrayList<>();
        for (Showtime showtime : showtimes) {
            Venue venue = venues.get(showtime.getVenueId());
            Hall hall = halls.get(showtime.getHallId());
            validateShowtimeRelation(showtime, venue, hall);
            if (Integer.valueOf(ACTIVE_STATUS).equals(venue.getStatus())) {
                result.add(toShowtimeVO(showtime, venue, hall));
            }
        }
        return result;
    }

    private Activity requireActiveActivity(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        Activity activity = activityMapper.selectById(id);
        if (activity == null || !Integer.valueOf(ACTIVE_STATUS).equals(activity.getStatus())) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    private Map<Long, Venue> indexVenues(List<Venue> values) {
        Map<Long, Venue> result = new HashMap<>();
        for (Venue venue : values) {
            if (venue.getId() == null || result.put(venue.getId(), venue) != null) {
                throw internalError("duplicate or unidentified venue in activity showtimes");
            }
        }
        return result;
    }

    private Map<Long, Activity> indexActivities(List<Activity> values) {
        Map<Long, Activity> result = new HashMap<>();
        for (Activity activity : values) {
            if (activity.getId() == null || result.put(activity.getId(), activity) != null) {
                throw internalError("duplicate or unidentified nearby activity");
            }
        }
        return result;
    }

    private Map<Long, Hall> indexHalls(List<Hall> values) {
        Map<Long, Hall> result = new HashMap<>();
        for (Hall hall : values) {
            if (hall.getId() == null || result.put(hall.getId(), hall) != null) {
                throw internalError("duplicate or unidentified hall in activity showtimes");
            }
        }
        return result;
    }

    private void validateShowtimeRelation(Showtime showtime, Venue venue, Hall hall) {
        boolean invalid = venue == null
                || hall == null
                || hall.getVenueId() == null
                || !showtime.getVenueId().equals(hall.getVenueId());
        if (invalid) {
            throw internalError("invalid fixed-seat activity showtime relation, showtimeId="
                    + showtime.getId());
        }
    }

    private void validateShowtimeCore(Showtime showtime) {
        boolean invalid = showtime == null
                || showtime.getId() == null
                || showtime.getVenueId() == null
                || showtime.getHallId() == null
                || showtime.getStartTime() == null
                || showtime.getEndTime() == null
                || !showtime.getEndTime().isAfter(showtime.getStartTime())
                || showtime.getBasePrice() == null
                || showtime.getTotalSeats() == null
                || showtime.getTotalSeats() <= 0
                || showtime.getAvailableSeats() == null
                || showtime.getAvailableSeats() < 0
                || showtime.getAvailableSeats() > showtime.getTotalSeats();
        if (invalid) {
            throw internalError("invalid fixed-seat activity showtime, showtimeId="
                    + (showtime == null ? null : showtime.getId()));
        }
    }

    private List<NearbyVenueCandidate> queryGeoCandidates(ActivityNearbyQueryDTO query,
                                                           String city,
                                                           List<VenueType> venueTypes,
                                                           long candidateLimit) {
        Map<Long, NearbyVenueCandidate> candidates = new HashMap<>();
        for (VenueType venueType : venueTypes) {
            String key = RedisConstants.VENUE_GEO_KEY + city + ":" + venueType.name();
            GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults;
            try {
                geoResults = stringRedisTemplate.opsForGeo().radius(
                        key,
                        new Circle(
                                new Point(query.getX(), query.getY()),
                                new Distance(query.getDistance() / 1000D, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(candidateLimit)
                );
            } catch (RuntimeException exception) {
                log.error("Redis GEO nearby activity query failed, key={}", key, exception);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            if (geoResults == null) {
                continue;
            }
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : geoResults) {
                Long venueId = parseGeoVenueId(result);
                double distance = parseGeoDistance(result);
                NearbyVenueCandidate candidate = candidates.get(venueId);
                if (candidate == null) {
                    candidate = new NearbyVenueCandidate(venueId, distance);
                    candidates.put(venueId, candidate);
                } else if (distance < candidate.getDistance()) {
                    candidate.setDistance(distance);
                }
                candidate.addSourceDistance(venueType, distance);
            }
        }
        List<NearbyVenueCandidate> result = new ArrayList<>(candidates.values());
        result.sort(Comparator.comparingDouble(NearbyVenueCandidate::getDistance)
                .thenComparing(NearbyVenueCandidate::getVenueId));
        return result;
    }

    private Long parseGeoVenueId(GeoResult<RedisGeoCommands.GeoLocation<String>> result) {
        try {
            return Long.valueOf(result.getContent().getName());
        } catch (RuntimeException exception) {
            throw internalError("invalid venue GEO member");
        }
    }

    private double parseGeoDistance(GeoResult<RedisGeoCommands.GeoLocation<String>> result) {
        if (result == null || result.getDistance() == null) {
            throw internalError("missing venue GEO distance");
        }
        double distance = result.getDistance().in(Metrics.KILOMETERS).getValue() * 1000D;
        if (Double.isNaN(distance) || Double.isInfinite(distance) || distance < 0D) {
            throw internalError("invalid venue GEO distance");
        }
        return distance;
    }

    private boolean isValidNearbyVenue(NearbyVenueCandidate candidate, Venue venue, String city) {
        if (venue == null
                || !Integer.valueOf(ACTIVE_STATUS).equals(venue.getStatus())
                || !city.equals(venue.getCity())
                || !isValidCoordinate(venue.getLongitude(), -180D, 180D)
                || !isValidCoordinate(venue.getLatitude(), -90D, 90D)) {
            return false;
        }
        try {
            return candidate.useSourceDistance(VenueType.fromDbValue(venue.getType()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isValidCoordinate(BigDecimal coordinate, double min, double max) {
        return coordinate != null
                && coordinate.compareTo(BigDecimal.valueOf(min)) >= 0
                && coordinate.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private void validateNearbyShowtime(Showtime showtime) {
        validateShowtimeCore(showtime);
        if (!ACTIVITY_BIZ_TYPE.equals(showtime.getBizType())
                || showtime.getBizId() == null
                || !Integer.valueOf(ACTIVE_STATUS).equals(showtime.getStatus())) {
            throw internalError("invalid nearby activity showtime, showtimeId="
                    + showtime.getId());
        }
    }

    private int compareChoice(NearbyActivityChoice left, NearbyActivityChoice right) {
        int compared = Double.compare(left.getDistance(), right.getDistance());
        if (compared != 0) {
            return compared;
        }
        compared = left.getVenue().getId().compareTo(right.getVenue().getId());
        if (compared != 0) {
            return compared;
        }
        compared = left.getShowtime().getStartTime()
                .compareTo(right.getShowtime().getStartTime());
        return compared != 0
                ? compared
                : left.getShowtime().getId().compareTo(right.getShowtime().getId());
    }

    private ActivityNearbyVO toNearbyVO(NearbyActivityChoice choice) {
        Activity activity = choice.getActivity();
        Venue venue = choice.getVenue();
        Showtime showtime = choice.getShowtime();
        ActivityNearbyVO result = new ActivityNearbyVO();
        result.setId(activity.getId());
        result.setTitle(activity.getTitle());
        result.setCoverUrl(activity.getCoverUrl());
        result.setType(activity.getType());
        result.setPriceMin(activity.getPriceMin());
        result.setPriceMax(activity.getPriceMax());
        result.setNearestVenueId(venue.getId());
        result.setNearestVenueName(venue.getName());
        result.setNearestVenueType(VenueType.fromDbValue(venue.getType()));
        result.setNearestVenueAddress(venue.getAddress());
        result.setVenueCity(venue.getCity());
        result.setDistance(choice.getDistance());
        result.setNextShowtimeId(showtime.getId());
        result.setNextStartTime(showtime.getStartTime());
        result.setNextAvailableSeats(showtime.getAvailableSeats());
        return result;
    }

    private BusinessException internalError(String message) {
        log.error(message);
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private ActivityListItemVO toListItem(Activity activity) {
        ActivityListItemVO result = new ActivityListItemVO();
        copyDiscoveryFields(activity, result);
        return result;
    }

    private void copyDiscoveryFields(Activity activity, ActivityListItemVO result) {
        result.setId(activity.getId());
        result.setTitle(activity.getTitle());
        result.setCoverUrl(activity.getCoverUrl());
        result.setType(activity.getType());
        result.setCity(activity.getCity());
        result.setPriceMin(activity.getPriceMin());
        result.setPriceMax(activity.getPriceMax());
    }

    private void copyDiscoveryFields(Activity activity, ActivityDetailVO result) {
        result.setId(activity.getId());
        result.setTitle(activity.getTitle());
        result.setCoverUrl(activity.getCoverUrl());
        result.setType(activity.getType());
        result.setCity(activity.getCity());
        result.setPriceMin(activity.getPriceMin());
        result.setPriceMax(activity.getPriceMax());
    }

    private ActivityShowtimeVO toShowtimeVO(Showtime showtime, Venue venue, Hall hall) {
        ActivityShowtimeVO result = new ActivityShowtimeVO();
        result.setShowtimeId(showtime.getId());
        result.setVenueId(venue.getId());
        result.setVenueName(venue.getName());
        result.setHallId(hall.getId());
        result.setHallName(hall.getName());
        result.setStartTime(showtime.getStartTime());
        result.setEndTime(showtime.getEndTime());
        result.setBasePrice(showtime.getBasePrice());
        result.setAvailableSeats(showtime.getAvailableSeats());
        return result;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static final class NearbyVenueCandidate {
        private final Long venueId;
        private double distance;
        private final Map<VenueType, Double> sourceDistances = new EnumMap<>(VenueType.class);

        private NearbyVenueCandidate(Long venueId, double distance) {
            this.venueId = venueId;
            this.distance = distance;
        }

        private Long getVenueId() {
            return venueId;
        }

        private double getDistance() {
            return distance;
        }

        private void setDistance(double distance) {
            this.distance = distance;
        }

        private void addSourceDistance(VenueType venueType, double sourceDistance) {
            sourceDistances.merge(venueType, sourceDistance, Math::min);
        }

        private boolean useSourceDistance(VenueType venueType) {
            Double sourceDistance = sourceDistances.get(venueType);
            if (sourceDistance == null) {
                return false;
            }
            distance = sourceDistance;
            return true;
        }
    }

    private static final class NearbyActivityChoice {
        private final Activity activity;
        private final Venue venue;
        private final Showtime showtime;
        private final double distance;

        private NearbyActivityChoice(Activity activity,
                                     Venue venue,
                                     Showtime showtime,
                                     double distance) {
            this.activity = activity;
            this.venue = venue;
            this.showtime = showtime;
            this.distance = distance;
        }

        private Activity getActivity() {
            return activity;
        }

        private Venue getVenue() {
            return venue;
        }

        private Showtime getShowtime() {
            return showtime;
        }

        private double getDistance() {
            return distance;
        }
    }
}
