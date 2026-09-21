package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.dto.AdminActivityShowtimeQueryDTO;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.service.ActivityShowtimeAdminService;
import com.movielife.vo.AdminActivityShowtimeVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityShowtimeAdminServiceImpl implements ActivityShowtimeAdminService {

    private static final String ACTIVITY_BIZ_TYPE = "ACTIVITY";

    private final VenueStaffMapper venueStaffMapper;
    private final ShowtimeMapper showtimeMapper;
    private final ActivityMapper activityMapper;
    private final HallMapper hallMapper;
    private final ActivityShowtimeCreationTransactionService creationTransactionService;

    @Override
    public PageVO<AdminActivityShowtimeVO> queryShowtimes(
            Long userId, Long venueId, AdminActivityShowtimeQueryDTO query) {
        validateUserAndVenue(userId, venueId);
        if (query == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        validateQuery(query);
        validateDateRange(query);
        requireManagementPermission(userId, venueId);

        LambdaQueryWrapper<Showtime> wrapper = new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getBizType, ACTIVITY_BIZ_TYPE)
                .eq(Showtime::getVenueId, venueId)
                .eq(query.getShowtimeStatus() != null,
                        Showtime::getStatus, query.getShowtimeStatus())
                .ge(query.getStartDate() != null,
                        Showtime::getStartTime, startOfDay(query.getStartDate()))
                .lt(query.getEndDate() != null,
                        Showtime::getStartTime, startOfNextDay(query.getEndDate()))
                .orderByDesc(Showtime::getStartTime)
                .orderByDesc(Showtime::getId);

        Page<Showtime> page = showtimeMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<AdminActivityShowtimeVO> items = mapShowtimes(page.getRecords());
        return new PageVO<>(items, page.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public AdminActivityShowtimeVO queryShowtime(Long userId, Long venueId, Long showtimeId) {
        validateUserAndVenue(userId, venueId);
        if (showtimeId == null || showtimeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        requireManagementPermission(userId, venueId);

        Showtime showtime = showtimeMapper.selectOne(new LambdaQueryWrapper<Showtime>()
                .eq(Showtime::getId, showtimeId)
                .eq(Showtime::getVenueId, venueId)
                .eq(Showtime::getBizType, ACTIVITY_BIZ_TYPE));
        if (showtime == null) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }
        return mapShowtimes(Collections.singletonList(showtime)).get(0);
    }

    @Override
    public AdminActivityShowtimeVO createShowtime(
            Long userId, Long venueId, CreateActivityShowtimeDTO request) {
        validateUserAndVenue(userId, venueId);
        ActivityShowtimeCreationCommand command = ActivityShowtimeCreationCommand.from(request);
        requireManagementPermission(userId, venueId);

        Showtime existing = showtimeMapper.selectByVenueAndCreateRequestId(
                venueId, command.getRequestId());
        if (existing != null) {
            return queryShowtime(userId, venueId,
                    requireMatchingCreation(existing, venueId, command));
        }
        if (!command.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }

        Long showtimeId;
        try {
            showtimeId = creationTransactionService.create(userId, venueId, command);
        } catch (DuplicateKeyException exception) {
            Showtime concurrent = showtimeMapper.selectByVenueAndCreateRequestId(
                    venueId, command.getRequestId());
            if (concurrent == null) {
                throw exception;
            }
            showtimeId = requireMatchingCreation(concurrent, venueId, command);
        }
        return queryShowtime(userId, venueId, showtimeId);
    }

    private void validateUserAndVenue(Long userId, Long venueId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (venueId == null || venueId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private void validateDateRange(AdminActivityShowtimeQueryDTO query) {
        if (query.getStartDate() != null && query.getEndDate() != null
                && query.getStartDate().isAfter(query.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private void validateQuery(AdminActivityShowtimeQueryDTO query) {
        if (query.getPage() == null || query.getPage() < 1) {
            throw new BusinessException(ErrorCode.INVALID_PAGE);
        }
        if (query.getPageSize() == null || query.getPageSize() < 1
                || query.getPageSize() > 100) {
            throw new BusinessException(ErrorCode.PAGE_SIZE_TOO_LARGE);
        }
        if (query.getShowtimeStatus() != null
                && (query.getShowtimeStatus() < 0 || query.getShowtimeStatus() > 2)) {
            throw new BusinessException(ErrorCode.INVALID_SHOWTIME_STATUS);
        }
    }

    private void requireManagementPermission(Long userId, Long venueId) {
        if (venueStaffMapper.selectActiveId(userId, venueId) == null) {
            throw new BusinessException(ErrorCode.VENUE_MANAGE_FORBIDDEN);
        }
    }

    private Long requireMatchingCreation(Showtime existing, Long venueId,
                                          ActivityShowtimeCreationCommand command) {
        if (existing.getId() == null || !command.matches(existing, venueId)) {
            throw new BusinessException(ErrorCode.SHOWTIME_CREATE_REQUEST_MISMATCH);
        }
        return existing.getId();
    }

    private LocalDateTime startOfDay(java.time.LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime startOfNextDay(java.time.LocalDate date) {
        if (date == null) {
            return null;
        }
        try {
            return date.plusDays(1).atStartOfDay();
        } catch (DateTimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private List<AdminActivityShowtimeVO> mapShowtimes(List<Showtime> showtimes) {
        if (showtimes == null || showtimes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> activityIds = showtimes.stream()
                .map(Showtime::getBizId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> hallIds = showtimes.stream()
                .map(Showtime::getHallId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (activityIds.contains(null) || hallIds.contains(null)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Map<Long, Activity> activities = activityMapper.selectBatchIds(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));
        Map<Long, Hall> halls = hallMapper.selectBatchIds(hallIds).stream()
                .collect(Collectors.toMap(Hall::getId, Function.identity()));
        return showtimes.stream()
                .map(showtime -> toVO(showtime, activities.get(showtime.getBizId()),
                        halls.get(showtime.getHallId())))
                .collect(Collectors.toList());
    }

    private AdminActivityShowtimeVO toVO(Showtime showtime, Activity activity, Hall hall) {
        validateRelation(showtime, activity, hall);
        AdminActivityShowtimeVO vo = new AdminActivityShowtimeVO();
        vo.setShowtimeId(showtime.getId());
        vo.setActivityId(activity.getId());
        vo.setActivityTitle(activity.getTitle());
        vo.setHallId(hall.getId());
        vo.setHallName(hall.getName());
        vo.setStartTime(showtime.getStartTime());
        vo.setEndTime(showtime.getEndTime());
        vo.setBasePrice(showtime.getBasePrice());
        vo.setStatus(showtime.getStatus());
        vo.setTotalSeats(showtime.getTotalSeats());
        vo.setAvailableSeats(showtime.getAvailableSeats());
        return vo;
    }

    private void validateRelation(Showtime showtime, Activity activity, Hall hall) {
        if (showtime == null || showtime.getId() == null
                || !ACTIVITY_BIZ_TYPE.equals(showtime.getBizType())
                || showtime.getVenueId() == null || showtime.getBizId() == null
                || showtime.getHallId() == null || activity == null || activity.getId() == null
                || hall == null || hall.getId() == null || hall.getVenueId() == null
                || !showtime.getVenueId().equals(hall.getVenueId())
                || showtime.getTotalSeats() == null || showtime.getAvailableSeats() == null
                || showtime.getTotalSeats() < 0 || showtime.getAvailableSeats() < 0
                || showtime.getAvailableSeats() > showtime.getTotalSeats()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
