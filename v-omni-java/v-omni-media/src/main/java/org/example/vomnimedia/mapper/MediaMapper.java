package org.example.vomnimedia.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;

@Mapper
public interface MediaMapper {

    @Insert("INSERT INTO u_media(id, state, create_time, update_time, url, user_id, title) " +
            "VALUES(#{id}, #{state}, NOW(), NOW(), #{url}, #{userId}, #{title})")
    int insertUser(MediaPo mediaPo);

    @Select("""
        SELECT m.title AS title,
            u.username AS author
        FROM u_media m
        INNER JOIN u_user u ON m.user_id = u.id
        WHERE m.id = #{id}
        """)
    DocumentVectorMediaPo selectMediaWithAuthor(@Param("id") Long id);

    @Update("""
        UPDATE u_media
        SET state = #{state},
            url = #{url}
        WHERE id = #{id}
        """)
    int updateStateAndUrl(@Param("id") Long id,
                          @Param("state") String state,
                          @Param("url") String url);
}
