package com.movielife.service;

import com.movielife.entity.Movie;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.PostMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.service.impl.CollectionServiceImpl;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionServiceImplTest {

    @Mock private CollectionMapper collectionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private PostMapper postMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private JsonListUtils jsonListUtils;

    private CollectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CollectionServiceImpl(collectionMapper, movieMapper, postMapper,
                reviewMapper, jsonListUtils);
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void repeatedCollectShouldBeIdempotent() {
        Movie movie = new Movie();
        movie.setId(2L);
        when(movieMapper.selectById(2L)).thenReturn(movie);
        when(collectionMapper.selectCount(any())).thenReturn(1L);

        service.collect("movie", 2L);

        verify(collectionMapper, never()).insert(any());
    }

    @Test
    void unsupportedTargetTypeShouldBeRejected() {
        assertThatThrownBy(() -> service.collect("ACTIVITY", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_COLLECTION_TARGET_TYPE);
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
