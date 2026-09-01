package com.movielife.service;

import com.movielife.dto.FollowQueryDTO;
import com.movielife.vo.FollowUserVO;
import com.movielife.vo.PageVO;

import java.util.List;

public interface FollowService {
    void follow(Long userId);
    void unfollow(Long userId);
    List<FollowUserVO> common(Long userId);
    PageVO<FollowUserVO> following(Long userId, FollowQueryDTO query);
    PageVO<FollowUserVO> followers(Long userId, FollowQueryDTO query);
}
