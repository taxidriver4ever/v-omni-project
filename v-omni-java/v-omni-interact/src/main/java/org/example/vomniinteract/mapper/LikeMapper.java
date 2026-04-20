package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.LikePo;

@Mapper
public interface LikeMapper {
    @Insert("INSERT INTO u_like(id, user_id, media_id, create_time) " +
            "VALUES(#{id}, #{userId}, #{mediaId}, #{createTime}) ")
    int insertLike(LikePo likePo);

    @Delete("DELETE FROM u_like WHERE user_id = #{userId} AND media_id = #{mediaId}")
    int deleteLike(@Param("userId") Long userId, @Param("mediaId") Long mediaId);

    @Select("SELECT id "+
            "FROM u_like " +
            "WHERE user_id = #{userId} AND media_id = #{mediaId}")
    Long selectLikeIdByUserIdAndMediaId(@Param("userId")Long userId, @Param("mediaId")Long mediaId);
}
