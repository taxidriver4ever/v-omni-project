package org.example.vomnimedia.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;

@Mapper
public interface MediaMapper {

    @Insert("INSERT INTO u_media(id, state, create_time, update_time, user_id, title, deleted) " +
            "VALUES(#{id}, #{state}, NOW(), NOW(), #{userId}, #{title}, 0)")
    int insertUser(MediaPo mediaPo);

    @Select("""
        SELECT m.id AS id,
            m.title AS title,
            u.username AS author
        FROM u_media m
        INNER JOIN u_user u ON m.user_id = u.id
        WHERE m.id = #{id}
        """)
    DocumentVectorMediaPo selectMediaWithAuthor(@Param("id") Long id);

    @Select("""
        SELECT u.username AS author
        FROM u_media m
        INNER JOIN u_user u ON m.user_id = u.id
        WHERE m.id = #{id}
        """)
    String selectAuthorById(@Param("id") Long id);

    @Update("""
        UPDATE u_media
        SET state = #{state}, update_time = NOW()
        WHERE id = #{id}
        """)
    int updateStateAndUrl(@Param("id") Long id,
                          @Param("state") String state);

    @Select("""
        SELECT user_id AS  userId
        FROM u_media
        WHERE id = #{id}
        """)
    Long selectUserIdById(@Param("id") Long id);

    @Update("UPDATE u_media SET deleted = 1, update_time = NOW() WHERE id = #{id}")
    int updateIsDeletedById(@Param("id") Long id);
}
