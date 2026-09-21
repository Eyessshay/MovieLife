package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.mapper.VenueStaffMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityShowtimeCreationTransactionService {

    private static final int ENABLED = 1;
    private static final int AVAILABLE = 0;

    private final VenueStaffMapper venueStaffMapper;
    private final VenueMapper venueMapper;
    private final ActivityMapper activityMapper;
    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final ShowtimeMapper showtimeMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;

    @Transactional
    public Long create(Long userId, Long venueId, ActivityShowtimeCreationCommand command) {
        if (userId == null || venueId == null || command == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        requireManagementPermission(userId, venueId);
        Showtime existing = showtimeMapper.selectByVenueAndCreateRequestId(
                venueId, command.getRequestId());
        if (existing != null) {
            return requireMatchingRequest(existing, venueId, command);
        }

        Venue venue = venueMapper.selectOne(new LambdaQueryWrapper<Venue>()
                .eq(Venue::getId, venueId)
                .eq(Venue::getStatus, ENABLED));
        if (venue == null) {
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }
        Activity activity = activityMapper.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getId, command.getActivityId())
                .eq(Activity::getStatus, ENABLED));
        if (activity == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        Hall hall = hallMapper.selectByIdForUpdate(command.getHallId());
        if (hall == null || !venueId.equals(hall.getVenueId())) {
            throw new BusinessException(ErrorCode.HALL_NOT_FOUND);
        }

        Showtime overlap = showtimeMapper.selectOverlappingShowtimeForUpdate(command.getHallId(),
                command.getStartTime(), command.getEndTime());
        if (overlap != null && command.getRequestId().equals(overlap.getCreateRequestId())) {
            return requireMatchingRequest(overlap, venueId, command);
        }
        if (overlap != null) {
            throw new BusinessException(ErrorCode.SHOWTIME_SCHEDULE_CONFLICT);
        }

        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getHallId, command.getHallId())
                .eq(Seat::getBaseStatus, ENABLED)
                .orderByAsc(Seat::getId));
        if (seats.isEmpty()) {
            throw new BusinessException(ErrorCode.SHOWTIME_SEAT_REQUIRED);
        }

        Showtime showtime = buildShowtime(venueId, command, seats.size());
        if (showtimeMapper.insert(showtime) != 1 || showtime.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<ShowtimeSeat> showtimeSeats = buildShowtimeSeats(
                showtime.getId(), seats, command);
        if (showtimeSeatMapper.insertBatch(showtimeSeats) != seats.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return showtime.getId();
    }

    private void requireManagementPermission(Long userId, Long venueId) {
        if (venueStaffMapper.selectActiveId(userId, venueId) == null) {
            throw new BusinessException(ErrorCode.VENUE_MANAGE_FORBIDDEN);
        }
    }

    private Long requireMatchingRequest(Showtime existing, Long venueId,
                                        ActivityShowtimeCreationCommand command) {
        if (existing.getId() == null || !command.matches(existing, venueId)) {
            throw new BusinessException(ErrorCode.SHOWTIME_CREATE_REQUEST_MISMATCH);
        }
        return existing.getId();
    }

    private Showtime buildShowtime(Long venueId, ActivityShowtimeCreationCommand command,
                                   int seatCount) {
        Showtime showtime = new Showtime();
        showtime.setBizType("ACTIVITY");
        showtime.setBizId(command.getActivityId());
        showtime.setVenueId(venueId);
        showtime.setHallId(command.getHallId());
        showtime.setCreateRequestId(command.getRequestId());
        showtime.setStartTime(command.getStartTime());
        showtime.setEndTime(command.getEndTime());
        showtime.setBasePrice(command.getBasePrice());
        showtime.setTotalSeats(seatCount);
        showtime.setAvailableSeats(seatCount);
        showtime.setStatus(1);
        return showtime;
    }

    private List<ShowtimeSeat> buildShowtimeSeats(Long showtimeId, List<Seat> seats,
                                                   ActivityShowtimeCreationCommand command) {
        List<ShowtimeSeat> states = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            if (seat == null || seat.getId() == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            ShowtimeSeat state = new ShowtimeSeat();
            state.setShowtimeId(showtimeId);
            state.setSeatId(seat.getId());
            state.setStatus(AVAILABLE);
            state.setPrice(command.getBasePrice());
            state.setLockOrderId(null);
            state.setVersion(0);
            states.add(state);
        }
        return states;
    }
}
