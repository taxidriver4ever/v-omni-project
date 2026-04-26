package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.CollectionPo;
import org.example.vomniinteract.po.CommentLikePo;
import org.example.vomniinteract.po.CommentPo;
import org.example.vomniinteract.po.UserPo;

import java.util.List;

@Mapper
public interface CommentMapper {
    @Insert("INSERT INTO u_comment(id, user_id, parent_id, root_id, media_id, content ,deleted, create_time)" +
            "VALUES(#{id},#{userId},#{parentId},#{rootId},#{mediaId},#{content},0,#{createTime})")
    int insertComment(CommentPo commentPo);

    /**
     * 根据 root_id 逻辑删除整条评论树
     */
    @Update("UPDATE u_comment SET deleted = 1 WHERE root_id = #{rootId}")
    int deleteRepliesByRootId(@Param("rootId") Long rootId);

    /**
     * 根据 id 逻辑删除单条评论
     */
    @Update("UPDATE u_comment SET deleted = 1 WHERE id = #{id}")
    int deleteCommentById(@Param("id") Long id);

    @Select("""
            <script>
                SELECT id, username, avatar_path AS avatarPath
                FROM u_user
                WHERE id IN 
                <foreach item='id' collection='userIds' open='(' separator=',' close=')'>
                    #{id}
                </foreach>
            </script> 
        """)
    List<UserPo> selectUserInfosByIds(@Param("userIds") List<Long> userIds);


}
