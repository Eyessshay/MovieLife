package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.Activity;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.SeatVO;
import com.movielife.vo.ShowtimeDetailVO;
import com.movielife.vo.ShowtimeSeatMapVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShowtimeServiceImpl implements ShowtimeService {

    private static final String MOVIE_BIZ_TYPE = "MOVIE";
    private static final String ACTIVITY_BIZ_TYPE = "ACTIVITY";
    private static final int AVAILABLE_SEAT_STATUS = 0;
    private static final int ENABLED_STATUS = 1;

    private final ShowtimeMapper showtimeMapper;
    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;
    private final MovieMapper movieMapper;
    private final ActivityMapper activityMapper;
    private final SeatMapper seatMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;

    @Override
    public ShowtimeDetailVO queryShowtimeDetail(Long id) {
        ShowtimeContext context = getValidShowtimeContext(id);
        return toDetailVO(context);
    }

    @Override
    public ShowtimeSeatMapVO querySeatMap(Long showtimeId) {
        ShowtimeContext context = getValidShowtimeContext(showtimeId);
        List<Seat> seats = seatMapper.selectList(
                new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, context.hall.getId())
                        .eq(Seat::getBaseStatus, ENABLED_STATUS)
                        .orderByAsc(Seat::getRowNo)
                        .orderByAsc(Seat::getColNo)
        );
        if (seats.isEmpty()) {
            if (context.isActivity()) {
                throw invalidSeatMap(showtimeId, "activity hall has no enabled physical seat");
            }
            return toSeatMapVO(context, Collections.emptyList());
        }

        List<Long> seatIds = seats.stream().map(Seat::getId).collect(Collectors.toList());
        List<ShowtimeSeat> showtimeSeats = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, seatIds)
        );
        Map<Long, ShowtimeSeat> stateBySeatId = indexShowtimeSeats(showtimeId, seats, showtimeSeats);
        if (context.isActivity()) {
            validateActivitySeatMap(context.showtime, seats, stateBySeatId);
        }
        List<SeatVO> seatVOs = seats.stream()
                .map(seat -> toSeatVO(seat, stateBySeatId.get(seat.getId())))
                .collect(Collectors.toList());
        return toSeatMapVO(context, seatVOs);
    }

    private ShowtimeContext getValidShowtimeContext(Long showtimeId) {
        if (showtimeId == null || showtimeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }

        Showtime showtime = showtimeMapper.selectById(showtimeId);
        if (!isPubliclyAvailable(showtime)) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }
        if (showtime.getBizId() == null
                || showtime.getVenueId() == null
                || showtime.getHallId() == null) {
            log.error("Invalid showtime identifiers, showtimeId={}", showtimeId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Venue venue = venueMapper.selectById(showtime.getVenueId());
        Hall hall = hallMapper.selectById(showtime.getHallId());
        Movie movie = null;
        Activity activity = null;
        if (MOVIE_BIZ_TYPE.equals(showtime.getBizType())) {
            movie = movieMapper.selectById(showtime.getBizId());
        } else if (ACTIVITY_BIZ_TYPE.equals(showtime.getBizType())) {
            activity = activityMapper.selectById(showtime.getBizId());
        }
        if (!isRelationValid(showtime, venue, hall, movie, activity)) {
            log.error("Invalid showtime relation, showtimeId={}", showtimeId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())
                || (activity != null
                && !Integer.valueOf(ENABLED_STATUS).equals(activity.getStatus()))) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }
        return new ShowtimeContext(showtime, venue, hall, movie, activity);
    }

    private boolean isPubliclyAvailable(Showtime showtime) {
        return showtime != null
                && (MOVIE_BIZ_TYPE.equals(showtime.getBizType())
                || ACTIVITY_BIZ_TYPE.equals(showtime.getBizType()))
                && Integer.valueOf(ENABLED_STATUS).equals(showtime.getStatus())
                && showtime.getStartTime() != null
                && showtime.getStartTime().isAfter(LocalDateTime.now());
    }

    private boolean isRelationValid(Showtime showtime,
                                    Venue venue,
                                    Hall hall,
                                    Movie movie,
                                    Activity activity) {
        return venue != null
                && hall != null
                && showtime.getVenueId() != null
                && showtime.getVenueId().equals(hall.getVenueId())
                && ((MOVIE_BIZ_TYPE.equals(showtime.getBizType()) && movie != null && activity == null)
                || (ACTIVITY_BIZ_TYPE.equals(showtime.getBizType()) && activity != null && movie == null));
    }

    private ShowtimeDetailVO toDetailVO(ShowtimeContext context) {
        ShowtimeDetailVO vo = new ShowtimeDetailVO();
        vo.setId(context.showtime.getId());
        vo.setVenueId(context.venue.getId());
        vo.setVenueName(context.venue.getName());
        vo.setHallId(context.hall.getId());
        vo.setHallName(context.hall.getName());
        vo.setBizType(context.showtime.getBizType());
        if (context.movie != null) {
            vo.setMovieId(context.movie.getId());
            vo.setMovieName(context.movie.getTitle());
        } else {
            vo.setActivityId(context.activity.getId());
            vo.setActivityName(context.activity.getTitle());
        }
        vo.setStartTime(context.showtime.getStartTime());
        vo.setEndTime(context.showtime.getEndTime());
        vo.setBasePrice(context.showtime.getBasePrice());
        return vo;
    }

    private void validateActivitySeatMap(Showtime showtime,
                                         List<Seat> seats,
                                         Map<Long, ShowtimeSeat> stateBySeatId) {
        if (showtime.getTotalSeats() == null || showtime.getTotalSeats() != seats.size()) {
            throw invalidSeatMap(showtime.getId(), "activity total seat count mismatch");
        }

        int availableSeats = 0;
        for (Seat seat : seats) {
            ShowtimeSeat state = stateBySeatId.get(seat.getId());
            boolean invalid = state == null
                    || state.getShowtimeId() == null
                    || !showtime.getId().equals(state.getShowtimeId())
                    || state.getSeatId() == null
                    || !seat.getId().equals(state.getSeatId())
                    || state.getStatus() == null
                    || state.getStatus() < 0
                    || state.getStatus() > 2
                    || state.getPrice() == null
                    || state.getPrice().signum() < 0;
            if (invalid) {
                throw invalidSeatMap(showtime.getId(), "invalid activity showtime seat state");
            }
            if (state.getStatus() == AVAILABLE_SEAT_STATUS) {
                availableSeats++;
            }
        }
        if (showtime.getAvailableSeats() == null
                || showtime.getAvailableSeats() != availableSeats) {
            throw invalidSeatMap(showtime.getId(), "activity available seat count mismatch");
        }
    }

    private BusinessException invalidSeatMap(Long showtimeId, String reason) {
        log.error("Invalid fixed-seat activity map, showtimeId={}, reason={}", showtimeId, reason);
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private Map<Long, ShowtimeSeat> indexShowtimeSeats(Long showtimeId,
                                                       List<Seat> seats,
                                                       List<ShowtimeSeat> showtimeSeats) {
        Map<Long, ShowtimeSeat> stateBySeatId = new HashMap<>();
        for (ShowtimeSeat showtimeSeat : showtimeSeats) {
            if (stateBySeatId.put(showtimeSeat.getSeatId(), showtimeSeat) != null) {
                log.error("Duplicate showtime seat state, showtimeId={}, seatId={}",
                        showtimeId, showtimeSeat.getSeatId());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
        if (stateBySeatId.size() != seats.size()) {
            log.error("Incomplete showtime seat state, showtimeId={}, expected={}, actual={}",
                    showtimeId, seats.size(), stateBySeatId.size());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return stateBySeatId;
    }

    private SeatVO toSeatVO(Seat seat, ShowtimeSeat showtimeSeat) {
        SeatVO vo = new SeatVO();
        vo.setSeatId(seat.getId());
        vo.setRowNo(seat.getRowNo());
        vo.setColNo(seat.getColNo());
        vo.setSeatNo(seat.getSeatNo());
        vo.setSeatType(seat.getSeatType());
        vo.setStatus(showtimeSeat.getStatus());
        vo.setPrice(showtimeSeat.getPrice());
        return vo;
    }

    private ShowtimeSeatMapVO toSeatMapVO(ShowtimeContext context, List<SeatVO> seats) {
        ShowtimeSeatMapVO vo = new ShowtimeSeatMapVO();
        vo.setShowtimeId(context.showtime.getId());
        vo.setHallId(context.hall.getId());
        vo.setHallName(context.hall.getName());
        vo.setBasePrice(context.showtime.getBasePrice());
        vo.setSeats(seats);
        return vo;
    }

    private static final class ShowtimeContext {

        private final Showtime showtime;
        private final Venue venue;
        private final Hall hall;
        private final Movie movie;
        private final Activity activity;

        private ShowtimeContext(Showtime showtime,
                                Venue venue,
                                Hall hall,
                                Movie movie,
                                Activity activity) {
            this.showtime = showtime;
            this.venue = venue;
            this.hall = hall;
            this.movie = movie;
            this.activity = activity;
        }

        private boolean isActivity() {
            return activity != null;
        }
    }
}
