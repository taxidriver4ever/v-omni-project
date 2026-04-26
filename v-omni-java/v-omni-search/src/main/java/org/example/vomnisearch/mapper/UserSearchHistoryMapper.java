package org.example.vomnisearch.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.example.vomnisearch.po.UserSearchHistoryPo;

@Mapper
public interface UserSearchHistoryMapper {

    @Insert("INSERT INTO u_user_search_history(id, user_id, keyword, create_time, update_time) " +
            "VALUES (#{id}, #{userId}, #{keyword}, #{createTime}, #{updateTime}) " +
            "ON DUPLICATE KEY UPDATE update_time = #{updateTime}")
    int addUserSearchHistoryIfAbsentUpdateTime(UserSearchHistoryPo userSearchHistoryPo);
}
