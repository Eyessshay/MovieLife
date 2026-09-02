package com.movielife.service;

import com.movielife.dto.VenueQueryDTO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueListItemVO;

public interface VenueService {

    PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO);
}
