package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.LikePo;

import java.util.List;

@Mapper
public interface LikeMapper {

    int insertLikeBatch(@Param("list") List<LikePo> likeList);

    int deleteLikeBatch(@Param("list") List<LikePo> likeList);


    @Select("SELECT id "+
            "FROM u_like " +
            "WHERE user_id = #{userId} AND media_id = #{mediaId}")
    Long selectLikeIdByUserIdAndMediaId(@Param("userId")Long userId, @Param("mediaId")Long mediaId);

    @Select("SELECT user_id "+
            "FROM u_like " +
            "WHERE media_id = #{mediaId}")
    List<Long> selectUserIdByMediaId(@Param("mediaId")Long mediaId);
}
