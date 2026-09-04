package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.entity.Hall;
import com.movielife.entity.Movie;
import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.entity.Venue;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.HallMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.VenueMapper;
import com.movielife.vo.PageVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderItemVO;
import com.movielife.vo.TicketOrderListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketOrderQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String MOVIE_BIZ_TYPE = "MOVIE";

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ShowtimeMapper showtimeMapper;
    private final MovieMapper movieMapper;
    private final VenueMapper venueMapper;
    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;

    public PageVO<TicketOrderListItemVO> queryMyOrders(
            Long userId, Integer page, Integer pageSize) {
        validateUser(userId);
        validatePage(page, pageSize);

        Page<TicketOrder> orderPage = ticketOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, userId)
                        .orderByDesc(TicketOrder::getCreateTime)
                        .orderByDesc(TicketOrder::getId));
        List<TicketOrder> orders = orderPage.getRecords();
        if (orders.isEmpty()) {
            return new PageVO<>(Collections.emptyList(), orderPage.getTotal(), page, pageSize);
        }

        List<Long> orderIds = orders.stream()
                .map(TicketOrder::getId)
                .collect(Collectors.toList());
        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .in(TicketOrderItem::getOrderId, orderIds));
        Map<Long, Integer> seatCounts = new HashMap<>();
        for (TicketOrderItem item : items) {
            seatCounts.merge(item.getOrderId(), 1, Integer::sum);
        }
        if (!seatCounts.keySet().containsAll(orderIds)) {
            throw internalError();
        }

        Set<Long> showtimeIds = orders.stream()
                .map(TicketOrder::getShowtimeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Showtime> showtimes = mapById(
                showtimeMapper.selectBatchIds(showtimeIds), Showtime::getId);
        requireAll(showtimes, showtimeIds);

        Set<Long> movieIds = new LinkedHashSet<>();
        Set<Long> venueIds = new LinkedHashSet<>();
        Set<Long> hallIds = new LinkedHashSet<>();
        for (Showtime showtime : showtimes.values()) {
            if (!MOVIE_BIZ_TYPE.equals(showtime.getBizType())) {
                throw internalError();
            }
            movieIds.add(showtime.getBizId());
            venueIds.add(showtime.getVenueId());
            hallIds.add(showtime.getHallId());
        }

        Map<Long, Movie> movies = mapById(movieMapper.selectBatchIds(movieIds), Movie::getId);
        Map<Long, Venue> venues = mapById(venueMapper.selectBatchIds(venueIds), Venue::getId);
        Map<Long, Hall> halls = mapById(hallMapper.selectBatchIds(hallIds), Hall::getId);
        requireAll(movies, movieIds);
        requireAll(venues, venueIds);
        requireAll(halls, hallIds);

        List<TicketOrderListItemVO> result = new ArrayList<>(orders.size());
        for (TicketOrder order : orders) {
            Showtime showtime = showtimes.get(order.getShowtimeId());
            result.add(toListItem(order, showtime, movies.get(showtime.getBizId()),
                    venues.get(showtime.getVenueId()), halls.get(showtime.getHallId()),
                    seatCounts.get(order.getId())));
        }
        return new PageVO<>(result, orderPage.getTotal(), page, pageSize);
    }

    public TicketOrderDetailVO queryOrderDetail(Long userId, Long orderId) {
        validateUser(userId);
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }

        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        Showtime showtime = showtimeMapper.selectById(order.getShowtimeId());
        if (showtime == null || !MOVIE_BIZ_TYPE.equals(showtime.getBizType())) {
            throw internalError();
        }
        Movie movie = movieMapper.selectById(showtime.getBizId());
        Venue venue = venueMapper.selectById(showtime.getVenueId());
        Hall hall = hallMapper.selectById(showtime.getHallId());
        if (movie == null || venue == null || hall == null) {
            throw internalError();
        }

        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, orderId));
        if (items.isEmpty()) {
            throw internalError();
        }
        Set<Long> seatIds = items.stream()
                .map(TicketOrderItem::getSeatId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Seat> seats = mapById(seatMapper.selectBatchIds(seatIds), Seat::getId);
        requireAll(seats, seatIds);

        List<TicketOrderItemVO> itemVOs = items.stream()
                .map(item -> toItem(item, seats.get(item.getSeatId())))
                .sorted(Comparator.comparing(TicketOrderItemVO::getRowNo)
                        .thenComparing(TicketOrderItemVO::getColNo))
                .collect(Collectors.toList());
        return toDetail(order, showtime, movie, venue, hall, itemVOs);
    }

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void validatePage(Integer page, Integer pageSize) {
        if (page == null || page < 1) {
            throw new BusinessException(ErrorCode.INVALID_PAGE);
        }
        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PAGE_SIZE_TOO_LARGE);
        }
    }

    private TicketOrderListItemVO toListItem(
            TicketOrder order, Showtime showtime, Movie movie, Venue venue,
            Hall hall, Integer seatCount) {
        TicketOrderListItemVO vo = new TicketOrderListItemVO();
        vo.setOrderId(order.getId());
        vo.setShowtimeId(showtime.getId());
        vo.setMovieId(movie.getId());
        vo.setMovieName(movie.getTitle());
        vo.setVenueId(venue.getId());
        vo.setVenueName(venue.getName());
        vo.setHallId(hall.getId());
        vo.setHallName(hall.getName());
        vo.setStartTime(showtime.getStartTime());
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setExpireTime(order.getExpireTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setSeatCount(seatCount);
        return vo;
    }

    private TicketOrderItemVO toItem(TicketOrderItem item, Seat seat) {
        TicketOrderItemVO vo = new TicketOrderItemVO();
        vo.setSeatId(seat.getId());
        vo.setRowNo(seat.getRowNo());
        vo.setColNo(seat.getColNo());
        vo.setSeatNo(seat.getSeatNo());
        vo.setSeatType(seat.getSeatType());
        vo.setPrice(item.getPrice());
        vo.setTicketCode(item.getTicketCode());
        vo.setTicketStatus(item.getTicketStatus());
        return vo;
    }

    private TicketOrderDetailVO toDetail(
            TicketOrder order, Showtime showtime, Movie movie, Venue venue,
            Hall hall, List<TicketOrderItemVO> items) {
        TicketOrderDetailVO vo = new TicketOrderDetailVO();
        vo.setOrderId(order.getId());
        vo.setShowtimeId(showtime.getId());
        vo.setMovieId(movie.getId());
        vo.setMovieName(movie.getTitle());
        vo.setVenueId(venue.getId());
        vo.setVenueName(venue.getName());
        vo.setHallId(hall.getId());
        vo.setHallName(hall.getName());
        vo.setStartTime(showtime.getStartTime());
        vo.setEndTime(showtime.getEndTime());
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setExpireTime(order.getExpireTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setItems(items);
        return vo;
    }

    private <T> Map<Long, T> mapById(List<T> records, Function<T, Long> idGetter) {
        if (records == null) {
            throw internalError();
        }
        return records.stream().collect(Collectors.toMap(idGetter, Function.identity()));
    }

    private void requireAll(Map<Long, ?> records, Set<Long> ids) {
        if (records.size() != ids.size() || !records.keySet().containsAll(ids)) {
            throw internalError();
        }
    }

    private BusinessException internalError() {
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }
}
