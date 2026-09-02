package com.movielife.service;

import com.movielife.dto.VenueQueryDTO;
import com.movielife.vo.HallVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueListItemVO;

import java.util.List;

public interface VenueService {

    PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO);

    VenueDetailVO queryVenueDetail(Long id);

    List<HallVO> queryVenueHalls(Long venueId);
}
