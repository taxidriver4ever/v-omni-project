package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CommentLikeMapper {
    @Select("SELECT id FROM u_comment_like " +
            "WHERE user_id = #{userId} AND comment_id = #{commentId} LIMIT 1")
    Long selectLikeIdByUserIdAndCommentId(@Param("userId") Long userId, @Param("commentId") Long commentId);

    /**
     * 插入评论点赞记录
     * 使用 INSERT IGNORE 配合唯一索引防止重复消费导致的报错
     */
    @Insert("""
            INSERT IGNORE INTO u_comment_like (id, comment_id, user_id, create_time)
            VALUES (#{id}, #{commentId}, #{userId}, #{createTime})
            """)
    int insertCommentLike(@Param("id") Long id,
                          @Param("commentId") Long commentId,
                          @Param("userId") Long userId,
                          @Param("createTime") java.util.Date createTime);

    /**
     * 删除评论点赞记录（取消点赞）
     */
    @Delete("""
            DELETE FROM u_comment_like
            WHERE comment_id = #{commentId} AND user_id = #{userId}
            """)
    int deleteCommentLike(@Param("commentId") Long commentId,
                          @Param("userId") Long userId);

    /**
     * 根据用户ID和评论ID查询点赞记录ID
     * 用于 Redis 缓存失效时回源检查是否已点赞
     */
    @Select("""
            SELECT id FROM u_comment_like
            WHERE comment_id = #{commentId} AND user_id = #{userId}
            LIMIT 1
            """)
    Long selectIdByUserIdAndCommentId(@Param("userId") Long userId,
                                      @Param("commentId") Long commentId);


    @Select("SELECT user_id "+
            "FROM u_comment_like " +
            "WHERE comment_id = #{commentId} ")
    List<Long> selectUserIdByCommentId(@Param("commentId")Long commentId);
}

