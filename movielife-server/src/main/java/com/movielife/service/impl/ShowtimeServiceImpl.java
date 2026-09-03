package com.movielife.service.impl;

import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Showtime;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.ShowtimeService;
import com.movielife.vo.ShowtimeDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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

    @Override
    public ShowtimeDetailVO queryShowtimeDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }

        Showtime showtime = showtimeMapper.selectById(id);
        if (!isPubliclyAvailable(showtime)) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }

        Venue venue = venueMapper.selectById(showtime.getVenueId());
        Hall hall = hallMapper.selectById(showtime.getHallId());
        Movie movie = movieMapper.selectById(showtime.getBizId());
        if (!isRelationValid(showtime, venue, hall, movie)) {
            log.error("Invalid showtime relation, showtimeId={}", id);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND);
        }

        return toDetailVO(showtime, venue, hall, movie);
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
}
