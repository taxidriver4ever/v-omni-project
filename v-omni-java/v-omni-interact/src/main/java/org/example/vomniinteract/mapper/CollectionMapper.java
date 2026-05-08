package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.*;
import org.example.vomniinteract.po.CollectionPo;
import org.example.vomniinteract.po.LikePo;

import java.util.List;

@Mapper
public interface CollectionMapper {
    /**
     * 批量插入收藏记录
     * 使用 INSERT IGNORE 保证幂等性，防止重复收藏报错
     */
    int insertCollectionBatch(@Param("list") List<CollectionPo> collectionList);

    /**
     * 批量删除收藏记录
     * 使用行构造器 (user_id, media_id) 匹配复合索引，效率最高
     */
    int deleteCollectionBatch(@Param("list") List<CollectionPo> collectionList);

    @Select("SELECT id "+
            "FROM u_collection " +
            "WHERE user_id = #{userId} AND media_id = #{mediaId}")
    Long selectCollectionIdByUserIdAndMediaId(@Param("userId")Long userId, @Param("mediaId")Long mediaId);

    @Select("SELECT user_id "+
            "FROM u_collection " +
            "WHERE media_id = #{mediaId}")
    List<Long> selectUserIdByMediaId(@Param("mediaId")Long mediaId);

}
