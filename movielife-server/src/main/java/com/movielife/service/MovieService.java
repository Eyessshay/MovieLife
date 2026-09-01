package com.movielife.service;

import com.movielife.dto.MovieQueryDTO;
import com.movielife.vo.MovieDetailVO;
import com.movielife.vo.MovieListItemVO;
import com.movielife.vo.PageVO;

public interface MovieService {

    PageVO<MovieListItemVO> queryMovies(MovieQueryDTO query);

    MovieDetailVO queryMovieDetail(Long id);
}
