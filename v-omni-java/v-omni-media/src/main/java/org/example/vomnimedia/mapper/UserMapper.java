package org.example.vomnimedia.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.vomnimedia.dto.AvatarAndAuthorDto;

@Mapper
public interface UserMapper {
    @Select("""
        SELECT avatar_path AS avatarPath, username AS author
        FROM u_user
        WHERE id = #{id}
        """)
    AvatarAndAuthorDto selectAvatarAndAuthorByUserId(@Param("id") Long id);

}
