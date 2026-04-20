package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CommentLikeMapper {
    @Select("SELECT id FROM u_comment_like " +
            "WHERE user_id = #{userId} AND comment_id = #{commentId} LIMIT 1")
    Long selectLikeIdByUserIdAndCommentId(@Param("userId") Long userId, @Param("commentId") Long commentId);
}

