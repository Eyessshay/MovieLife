package com.movielife.service;

import com.movielife.dto.VenueQueryDTO;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.vo.HallVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueListItemVO;
import com.movielife.vo.VenueNearbyVO;
import com.movielife.vo.ShowtimeVO;

import java.time.LocalDate;
import java.util.List;

public interface VenueService {

    PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO);

    VenueDetailVO queryVenueDetail(Long id);

    List<HallVO> queryVenueHalls(Long venueId);

    List<VenueNearbyVO> queryNearbyVenues(VenueNearbyQueryDTO queryDTO);

    List<ShowtimeVO> queryVenueShowtimes(Long venueId, Long movieId, LocalDate date);
}
