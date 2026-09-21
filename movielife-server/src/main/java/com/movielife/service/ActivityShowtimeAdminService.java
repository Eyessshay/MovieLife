package com.movielife.service;

import com.movielife.dto.AdminActivityShowtimeQueryDTO;
import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.vo.AdminActivityShowtimeVO;
import com.movielife.vo.PageVO;

public interface ActivityShowtimeAdminService {

    PageVO<AdminActivityShowtimeVO> queryShowtimes(Long userId, Long venueId,
                                                    AdminActivityShowtimeQueryDTO query);

    AdminActivityShowtimeVO queryShowtime(Long userId, Long venueId, Long showtimeId);

    AdminActivityShowtimeVO createShowtime(Long userId, Long venueId,
                                           CreateActivityShowtimeDTO request);
}
