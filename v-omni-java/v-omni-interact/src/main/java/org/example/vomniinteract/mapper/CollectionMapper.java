package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.CollectionPo;

@Mapper
public interface CollectionMapper {
    @Insert("INSERT INTO u_collection(id, user_id, media_id, create_time) " +
            "VALUES(#{id}, #{userId}, #{mediaId}, #{createTime}) ")
    int insertCollection(CollectionPo collectionPo);

    @Delete("DELETE FROM u_collection WHERE user_id = #{userId} AND media_id = #{mediaId}")
    int deleteCollection(@Param("userId") Long userId, @Param("mediaId") Long mediaId);

    @Select("SELECT id "+
            "FROM u_collection " +
            "WHERE user_id = #{userId} AND media_id = #{mediaId}")
    Long selectCollectionIdByUserIdAndMediaId(@Param("userId")Long userId, @Param("mediaId")Long mediaId);

}
