package com.movielife.service;

import com.movielife.dto.CreatePostDTO;
import com.movielife.dto.PostQueryDTO;
import com.movielife.dto.UpdatePostDTO;
import com.movielife.vo.PageVO;
import com.movielife.vo.PostLikeVO;
import com.movielife.vo.PostVO;

public interface PostService {
    PostVO create(CreatePostDTO request);
    PageVO<PostVO> query(PostQueryDTO query);
    PostVO detail(Long id);
    PostVO update(Long id, UpdatePostDTO request);
    void delete(Long id);
    PostLikeVO like(Long id);
    PostLikeVO unlike(Long id);
}
