package com.movielife.service;

import com.movielife.vo.ShowtimeDetailVO;
import com.movielife.vo.ShowtimeSeatMapVO;

public interface ShowtimeService {

    ShowtimeDetailVO queryShowtimeDetail(Long id);

    ShowtimeSeatMapVO querySeatMap(Long showtimeId);
}
