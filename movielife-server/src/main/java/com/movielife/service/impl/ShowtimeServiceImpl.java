package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
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
    private static final int ENABLED_STATUS = 1;

    private final ShowtimeMapper showtimeMapper;
    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;
    private final MovieMapper movieMapper;
    private final SeatMapper seatMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;

    @Override
    public ShowtimeDetailVO queryShowtimeDetail(Long id) {
        ShowtimeContext context = getValidMovieShowtimeContext(id);
        return toDetailVO(context.showtime, context.venue, context.hall, context.movie);
    }

    @Override
    public ShowtimeSeatMapVO querySeatMap(Long showtimeId) {
        ShowtimeContext context = getValidMovieShowtimeContext(showtimeId);
        List<Seat> seats = seatMapper.selectList(
                new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, context.hall.getId())
                        .eq(Seat::getBaseStatus, ENABLED_STATUS)
                        .orderByAsc(Seat::getRowNo)
                        .orderByAsc(Seat::getColNo)
        );
        if (seats.isEmpty()) {
            return toSeatMapVO(context, Collections.emptyList());
        }

        List<Long> seatIds = seats.stream().map(Seat::getId).collect(Collectors.toList());
        List<ShowtimeSeat> showtimeSeats = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, seatIds)
        );
        Map<Long, ShowtimeSeat> stateBySeatId = indexShowtimeSeats(showtimeId, seats, showtimeSeats);
        List<SeatVO> seatVOs = seats.stream()
                .map(seat -> toSeatVO(seat, stateBySeatId.get(seat.getId())))
                .collect(Collectors.toList());
        return toSeatMapVO(context, seatVOs);
    }

    private ShowtimeContext getValidMovieShowtimeContext(Long showtimeId) {
        if (showtimeId == null || showtimeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }

        Showtime showtime = showtimeMapper.selectById(showtimeId);
        if (!isPubliclyAvailable(showtime)) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }

        Venue venue = venueMapper.selectById(showtime.getVenueId());
        Hall hall = hallMapper.selectById(showtime.getHallId());
        Movie movie = movieMapper.selectById(showtime.getBizId());
        if (!isRelationValid(showtime, venue, hall, movie)) {
            log.error("Invalid showtime relation, showtimeId={}", showtimeId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }
        return new ShowtimeContext(showtime, venue, hall, movie);
    }

    private boolean isPubliclyAvailable(Showtime showtime) {
        return showtime != null
                && MOVIE_BIZ_TYPE.equals(showtime.getBizType())
                && Integer.valueOf(ENABLED_STATUS).equals(showtime.getStatus())
                && showtime.getStartTime() != null
                && showtime.getStartTime().isAfter(LocalDateTime.now());
    }

    private boolean isRelationValid(Showtime showtime, Venue venue, Hall hall, Movie movie) {
        return venue != null
                && hall != null
                && movie != null
                && showtime.getVenueId().equals(hall.getVenueId());
    }

    private ShowtimeDetailVO toDetailVO(Showtime showtime, Venue venue, Hall hall, Movie movie) {
        ShowtimeDetailVO vo = new ShowtimeDetailVO();
        vo.setId(showtime.getId());
        vo.setVenueId(venue.getId());
        vo.setVenueName(venue.getName());
        vo.setHallId(hall.getId());
        vo.setHallName(hall.getName());
        vo.setMovieId(movie.getId());
        vo.setMovieName(movie.getTitle());
        vo.setStartTime(showtime.getStartTime());
        vo.setEndTime(showtime.getEndTime());
        vo.setBasePrice(showtime.getBasePrice());
        return vo;
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

        private ShowtimeContext(Showtime showtime, Venue venue, Hall hall, Movie movie) {
            this.showtime = showtime;
            this.venue = venue;
            this.hall = hall;
            this.movie = movie;
        }
    }
}
