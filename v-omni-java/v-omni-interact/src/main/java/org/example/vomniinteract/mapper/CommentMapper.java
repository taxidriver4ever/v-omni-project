package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.CollectionPo;
import org.example.vomniinteract.po.CommentLikePo;
import org.example.vomniinteract.po.CommentPo;
import org.example.vomniinteract.po.UserPo;

import java.util.List;

@Mapper
public interface CommentMapper {

    /**
     * 批量插入评论
     */
    int insertCommentBatch(@Param("list") List<CommentPo> list);

    /**
     * 批量级联删除
     * 1. 删除 id 在列表中的评论（针对普通删除和根评论本身）
     * 2. 删除 root_id 在列表中的评论（针对根评论下的所有子孙评论）
     */
    int deleteCommentsBatch(@Param("commentIds") List<Long> commentIds, @Param("rootIds") List<Long> rootIds);

    /**
     * 根据 id 逻辑删除单条评论
     */
    @Update("UPDATE u_comment SET deleted = 1 WHERE id = #{id}")
    int deleteCommentById(@Param("id") Long id);

    @Select("SELECT id "+
            "FROM u_comment " +
            "WHERE user_id = #{userId} and deleted = 0 ")
    List<Long> selectCommentIdByUserId(@Param("userId")Long mediaId);


}
