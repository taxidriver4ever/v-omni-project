package org.example.vomnisearch.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

@Data
public class DocumentUserBehaviorHistoryPo {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("media_id")
    private String mediaId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    @JsonProperty("create_time")
    private Date createTime;

    @JsonProperty("behavior_type")
    private String behaviorType;

    @JsonProperty("behavior_vector")
    private float[] behaviorVector;

}