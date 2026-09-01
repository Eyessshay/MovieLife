package com.movielife.service;

import com.movielife.dto.FeedQueryDTO;
import com.movielife.vo.PostVO;
import com.movielife.vo.ScrollVO;

public interface FeedService {
    ScrollVO<PostVO> queryFollowingFeed(FeedQueryDTO query);
}
