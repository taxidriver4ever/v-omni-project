package org.example.vomnimedia.domain.statemachine;

public enum MediaEvent {
    GET_PRE_SIGNATURE,      // 获取临时签名
    START_PROCESSING,       // 开始加工
    FINISH_EXTRACTION,      // 完成抽帧
    FINISH_DECODING,        // 完成解码
}
