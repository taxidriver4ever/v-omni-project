package org.example.vomniauth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.vomniauth.po.UserPo;

import java.util.List;

@Mapper
public interface UserMapper {

    // 1. 插入新用户 (配合你的雪花 ID)
    @Insert("INSERT INTO u_user(id, username, email, state, create_time, update_time) " +
            "VALUES(#{id}, #{username}, #{email} , #{state}, NOW(), NOW())")
    int insertUser(UserPo user);

    // 2. 根据邮箱查 ID (这就是你之前担心的优化点)
    @Select("SELECT id FROM u_user WHERE email = #{email}")
    Long findIdByEmail(@Param("email") String email);

    @Select("SELECT email FROM u_user")
    List<String> findAllEmails();

    @Select("SELECT username FROM u_user WHERE email = #{email}")
    String findUsernameByEmail(@Param("email") String email);

    @Select("SELECT state FROM u_user WHERE id = #{id}")
    String findStateById(@Param("id") Long id);
}
