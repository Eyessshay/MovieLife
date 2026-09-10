package com.movielife.service;

import com.movielife.dto.ActivityQueryDTO;
import com.movielife.dto.ActivityShowtimeQueryDTO;
import com.movielife.vo.ActivityDetailVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.ActivityShowtimeVO;
import com.movielife.vo.PageVO;

import java.util.List;

public interface ActivityService {

    PageVO<ActivityListItemVO> queryActivities(ActivityQueryDTO query);

    ActivityDetailVO queryActivityDetail(Long id);

    List<ActivityShowtimeVO> queryActivityShowtimes(
            Long activityId, ActivityShowtimeQueryDTO query);
}
