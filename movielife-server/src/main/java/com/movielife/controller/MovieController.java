package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.MovieQueryDTO;
import com.movielife.service.MovieService;
import com.movielife.vo.MovieDetailVO;
import com.movielife.vo.MovieListItemVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    @GetMapping
    public Result<PageVO<MovieListItemVO>> list(@Valid MovieQueryDTO query) {
        return Result.ok(movieService.queryMovies(query));
    }

    @GetMapping("/{id}")
    public Result<MovieDetailVO> detail(@PathVariable Long id) {
        return Result.ok(movieService.queryMovieDetail(id));
    }
}
