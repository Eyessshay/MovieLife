package com.movielife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_post")
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String images;
    private String relatedType;
    private Long relatedId;
    private Integer liked;
    private Integer comments;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
