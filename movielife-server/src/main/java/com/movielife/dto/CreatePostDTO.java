package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class CreatePostDTO {

    @NotBlank(message = "帖子标题不能为空")
    @Size(max = 128, message = "帖子标题不能超过128字")
    private String title;

    @NotBlank(message = "帖子内容不能为空")
    @Size(max = 5000, message = "帖子内容不能超过5000字")
    private String content;

    @Size(max = 9, message = "帖子图片不能超过9张")
    private List<String> images;

    private String relatedType;
    private Long relatedId;
}
