package org.example.vomniinteract.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.example.vomniinteract.po.UserCollectionPo;
import org.example.vomniinteract.po.UserLikePo;
import org.example.vomniinteract.po.VideoCommentPo;

@Mapper
public interface InteractMapper {

    @Insert("INSERT INTO u_user_like(id, like_user_id, media_id, deleted, create_time, update_time) " +
            "VALUES(#{id}, #{likeUserId}, #{mediaId}, #{deleted}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "deleted = VALUES(deleted), " +
            "update_time = NOW()")
    int upsertUserLike(UserLikePo userLikePo);


    @Insert("INSERT INTO u_user_like(id, like_user_id, media_id, deleted, create_time, update_time) " +
            "VALUES(#{id}, #{likeUserId}, #{mediaId}, #{deleted}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE " +
            "deleted = VALUES(deleted), " +
            "update_time = NOW()")
    int upsertUserCollection(UserCollectionPo userCollectionPo);


    @Insert("INSERT INTO u_video_comment(id, reply_user_id, parent_user_id, root_user_id, media_id, content ,deleted, create_time)" +
            "VALUES(#{id},#{replyUserId},#{parentUserId},#{rootUserId},#{mediaId},#{content},0,NOW())")
    int insertVideoComment(VideoCommentPo videoCommentPo);
}
