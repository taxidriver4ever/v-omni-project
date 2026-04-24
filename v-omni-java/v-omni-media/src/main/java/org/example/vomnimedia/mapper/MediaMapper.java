package org.example.vomnimedia.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;
import org.springframework.security.core.parameters.P;

import java.util.Date;
import java.util.Map;

@Mapper
public interface MediaMapper {

    @Insert("INSERT INTO u_media(id, state, create_time, update_time, user_id, title, deleted, cover_path) " +
            "VALUES(#{id}, #{state}, #{createTime}, #{updateTime}, #{userId}, #{title}, 0, #{coverPath})")
    int insertUser(MediaPo mediaPo);

    @Update("UPDATE u_media SET deleted = 1, update_time = #{updateTime} WHERE id = #{id} AND user_id = #{userId}")
    int updateIsDeletedByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId , @Param("updateTime") Date updateTime);
}
