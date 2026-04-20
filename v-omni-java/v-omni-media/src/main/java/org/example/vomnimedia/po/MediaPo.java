package org.example.vomnimedia.po;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.vomnimedia.domain.statemachine.MediaState;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * User 持久化对象
 * 对应数据库 users 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaPo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花算法生成的 ID
     * 注意：前端 JS 处理 Long 会丢失精度，建议在 ResultVO 中转为 String
     */
    private Long id;
    private String title;
    private Long userId;
    private String state;
    private int deleted;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone = "GMT+8")
    private Date updateTime;

    public MediaPo(Long id, Long userId, @NotNull MediaState mediaState, String title) {
        this.id = id;
        this.userId = userId;
        this.state = mediaState.toString();
        this.title = title;
    }
}

