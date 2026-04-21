package org.example.vomnimedia.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomnimedia.dto.AvatarAndAuthorDto;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;
import org.springframework.security.core.parameters.P;

import java.util.Date;
import java.util.Map;

@Mapper
public interface MediaMapper {

    @Insert("INSERT INTO u_media(id, state, create_time, update_time, user_id, title, deleted, cover_path) " +
            "VALUES(#{id}, #{state}, #{createTime}, #{updateTime}, #{userId}, #{title}, 0, #{cover_path})")
    int insertUser(MediaPo mediaPo);

    @Select("""
        SELECT m.id AS id,
            m.title AS title,
            u.avatar_path AS avatarPath,
            u.username AS author
        FROM u_media m
        INNER JOIN u_user u ON m.user_id = u.id
        WHERE m.id = #{id}
        """)
    DocumentVectorMediaPo selectMediaWithAuthor(@Param("id") Long id);

    @Update("""
        UPDATE u_media
        SET state = #{state}, update_time = #{updateTime}, cover_path = #{coverPath}
        WHERE id = #{id}
        """)
    int updateStateAndUrl(@Param("id") Long id,
                          @Param("state") String state,
                          @Param("updateTime") Date updateTime,
                          @Param("coverPath") String coverPath);

    @Select("""
        SELECT user_id AS  userId
        FROM u_media
        WHERE id = #{id}
        """)
    Long selectUserIdById(@Param("id") Long id);

    @Update("UPDATE u_media SET deleted = 1, update_time = #{updateTime} WHERE id = #{id}")
    int updateIsDeletedById(@Param("id") Long id, @Param("updateTime") Date updateTime);
}
