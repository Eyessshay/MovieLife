package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.dto.FollowQueryDTO;
import com.movielife.entity.Follow;
import com.movielife.entity.User;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.FollowMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.service.FollowService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.FollowUserVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final FollowMapper followMapper;
    private final UserMapper userMapper;

    @Override
    public void follow(Long targetUserId) {
        Long userId = requireUserId();
        validateUserId(targetUserId);
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }
        requireUser(targetUserId);
        if (exists(userId, targetUserId)) {
            return;
        }
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(targetUserId);
        follow.setCreateTime(LocalDateTime.now());
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException ignored) {
            // A concurrent idempotent PUT already created the same relation.
        }
    }

    @Override
    public void unfollow(Long targetUserId) {
        Long userId = requireUserId();
        validateUserId(targetUserId);
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, targetUserId));
    }

    @Override
    public List<FollowUserVO> common(Long targetUserId) {
        Long userId = requireUserId();
        validateUserId(targetUserId);
        requireUser(targetUserId);
        Set<Long> mine = followingIds(userId);
        mine.retainAll(followingIds(targetUserId));
        if (mine.isEmpty()) {
            return Collections.emptyList();
        }
        return userMapper.selectBatchIds(mine).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public PageVO<FollowUserVO> following(Long userId, FollowQueryDTO query) {
        validateUserId(userId);
        requireUser(userId);
        Page<Follow> page = followMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()),
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId)
                        .orderByDesc(Follow::getCreateTime)
                        .orderByDesc(Follow::getId)
        );
        List<Long> ids = page.getRecords().stream().map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        return new PageVO<>(usersInOrder(ids), page.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public PageVO<FollowUserVO> followers(Long userId, FollowQueryDTO query) {
        validateUserId(userId);
        requireUser(userId);
        Page<Follow> page = followMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()),
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFollowUserId, userId)
                        .orderByDesc(Follow::getCreateTime)
                        .orderByDesc(Follow::getId)
        );
        List<Long> ids = page.getRecords().stream().map(Follow::getUserId)
                .collect(Collectors.toList());
        return new PageVO<>(usersInOrder(ids), page.getTotal(), query.getPage(), query.getPageSize());
    }

    private List<FollowUserVO> usersInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().filter(users::containsKey).map(id -> toVO(users.get(id)))
                .collect(Collectors.toList());
    }

    private Set<Long> followingIds(Long userId) {
        return followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId))
                .stream().map(Follow::getFollowUserId).collect(Collectors.toCollection(HashSet::new));
    }

    private boolean exists(Long userId, Long targetUserId) {
        Long count = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, targetUserId));
        return count != null && count > 0;
    }

    private FollowUserVO toVO(User user) {
        return new FollowUserVO(user.getId(), user.getNickName(), user.getIcon(), user.getCity());
    }

    private void requireUser(Long id) {
        if (userMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private void validateUserId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
