package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.CommentLikePo;

import java.util.List;

@Mapper
public interface CommentLikeMapper {

    /**
     * 批量插入评论点赞
     */
    int insertCommentLikeBatch(@Param("list") List<CommentLikePo> list);

    /**
     * 批量取消评论点赞
     */
    int deleteCommentLikeBatch(@Param("list") List<CommentLikePo> list);

    @Select("SELECT user_id "+
            "FROM u_comment_like " +
            "WHERE comment_id = #{commentId} ")
    List<Long> selectUserIdByCommentId(@Param("commentId")Long commentId);
}

