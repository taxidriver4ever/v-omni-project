package org.example.vomnimedia.domain.statemachine;

public enum MediaState {

    INITIAL,                    // 初始化
    PREPARE_PUBLISH_MEDIA,      // 准备发布视频
    PROCESSING,                 // 加工中
    EXTRACT_FINISH,             // 抽帧结束
    DECODE_FINISH,              // 解码结束
    FINISHED,                   // 过程结束
    EXCEED_LIMIT,               // 尝试次数超出限制
    ERROR                       // 通用错误

}
