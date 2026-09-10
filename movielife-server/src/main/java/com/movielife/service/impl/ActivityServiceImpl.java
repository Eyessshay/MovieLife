package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.dto.ActivityQueryDTO;
import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.ActivityService;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    private final ActivityMapper activityMapper;
    private final ShowtimeMapper showtimeMapper;
    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;

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
}
