package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.dto.ShowtimeSeatInventoryStat;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ActivityShowtimeSaleStatusTransactionService {

    private static final int STOPPED = 0;
    private static final int ON_SALE = 1;

    private final VenueStaffMapper venueStaffMapper;
    private final VenueMapper venueMapper;
    private final ActivityMapper activityMapper;
    private final HallMapper hallMapper;
    private final ShowtimeMapper showtimeMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;

    @Transactional
    public Long stopSale(Long userId, Long venueId, Long showtimeId) {
        return changeSaleStatus(userId, venueId, showtimeId, STOPPED);
    }

    @Transactional
    public Long resumeSale(Long userId, Long venueId, Long showtimeId) {
        return changeSaleStatus(userId, venueId, showtimeId, ON_SALE);
    }

    private Long changeSaleStatus(Long userId, Long venueId, Long showtimeId,
                                  int targetStatus) {
        validateArguments(userId, venueId, showtimeId);
        requireManagementPermission(userId, venueId);
        Showtime showtime = showtimeMapper.selectActivityByIdAndVenueForUpdate(
                showtimeId, venueId);
        if (showtime == null) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();
        validateMutableShowtime(showtime, now);
        if (targetStatus == ON_SALE) {
            validateResumeFacts(showtime, venueId);
        }
        if (Integer.valueOf(targetStatus).equals(showtime.getStatus())) {
            return showtime.getId();
        }

        int expectedStatus = targetStatus == STOPPED ? ON_SALE : STOPPED;
        if (showtimeMapper.updateActivitySaleStatus(showtimeId, venueId,
                expectedStatus, targetStatus, now) != 1) {
            throw new BusinessException(ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT);
        }
        return showtime.getId();
    }

    private void validateArguments(Long userId, Long venueId, Long showtimeId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (venueId == null || venueId <= 0 || showtimeId == null || showtimeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private void requireManagementPermission(Long userId, Long venueId) {
        if (venueStaffMapper.selectActiveId(userId, venueId) == null) {
            throw new BusinessException(ErrorCode.VENUE_MANAGE_FORBIDDEN);
        }
    }

    private void validateMutableShowtime(Showtime showtime, LocalDateTime now) {
        if (showtime.getId() == null || showtime.getStartTime() == null
                || showtime.getEndTime() == null || showtime.getStatus() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!showtime.getStartTime().isAfter(now) || !showtime.getEndTime().isAfter(now)
                || (showtime.getStatus() != STOPPED && showtime.getStatus() != ON_SALE)) {
            throw new BusinessException(ErrorCode.SHOWTIME_SALE_STATUS_CONFLICT);
        }
    }

    private void validateResumeFacts(Showtime showtime, Long venueId) {
        Venue venue = venueMapper.selectOne(new LambdaQueryWrapper<Venue>()
                .eq(Venue::getId, venueId)
                .eq(Venue::getStatus, ON_SALE));
        if (venue == null) {
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }
        Activity activity = activityMapper.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getId, showtime.getBizId())
                .eq(Activity::getStatus, ON_SALE));
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        Hall hall = hallMapper.selectOne(new LambdaQueryWrapper<Hall>()
                .eq(Hall::getId, showtime.getHallId())
                .eq(Hall::getVenueId, venueId));
        if (hall == null) {
            throw new BusinessException(ErrorCode.HALL_NOT_FOUND);
        }

        ShowtimeSeatInventoryStat inventory = showtimeSeatMapper.selectInventoryStat(
                showtime.getId());
        if (inventory == null || inventory.getTotalCount() == null
                || inventory.getAvailableCount() == null
                || showtime.getTotalSeats() == null || showtime.getAvailableSeats() == null
                || showtime.getTotalSeats() <= 0 || showtime.getAvailableSeats() < 0
                || showtime.getAvailableSeats() > showtime.getTotalSeats()
                || inventory.getTotalCount().longValue() != showtime.getTotalSeats().longValue()
                || inventory.getAvailableCount().longValue()
                != showtime.getAvailableSeats().longValue()) {
            throw new BusinessException(ErrorCode.SHOWTIME_INVENTORY_INCONSISTENT);
        }
    }
}
