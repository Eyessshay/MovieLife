package com.movielife.service;

import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.ReviewQueryDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.vo.PageVO;
import com.movielife.vo.ReviewLikeVO;
import com.movielife.vo.ReviewVO;

public interface ReviewService {

    ReviewVO createReview(CreateReviewDTO request);

    PageVO<ReviewVO> queryReviews(ReviewQueryDTO query);

    ReviewVO queryReviewDetail(Long id);

    ReviewVO updateReview(Long id, UpdateReviewDTO request);

    void deleteReview(Long id);

    ReviewLikeVO likeReview(Long id);

    ReviewLikeVO unlikeReview(Long id);
}
