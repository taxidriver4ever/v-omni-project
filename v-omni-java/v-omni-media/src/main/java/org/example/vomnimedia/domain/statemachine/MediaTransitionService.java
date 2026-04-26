package org.example.vomnimedia.domain.statemachine;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;


@Slf4j
@Component
public class MediaTransitionService {

    @Resource
    private MediaAction mediaAction;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final Map<String, Map<MediaState, MediaRule>> rules = new HashMap<>();

    @PostConstruct
    public void initRules() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("lua/send_event.lua")) {
            String lua = new String(Objects.requireNonNull(is).readAllBytes(), StandardCharsets.UTF_8);

            String result = stringRedisTemplate.execute((RedisCallback<String>) connection -> {
                // 强制尝试发送原生命令
                Object response = connection.execute(
                        "FUNCTION",
                        "LOAD".getBytes(StandardCharsets.UTF_8),
                        "REPLACE".getBytes(StandardCharsets.UTF_8),
                        lua.getBytes(StandardCharsets.UTF_8)
                );
                return response != null ? "SUCCESS" : "FAIL";
            });
            log.info("Lua 函数库加载完成，SHA: {}", result);
        } catch (IOException e) {
            throw new RuntimeException("加载 Lua 脚本失败", e);
        }

        rules.put(MediaState.INITIAL + ":" + MediaEvent.GET_PRE_SIGNATURE,
                Map.of(
                        MediaState.PREPARE_PUBLISH_MEDIA,
                        MediaRule.builder()
                                .from(MediaState.INITIAL)
                                .on(MediaEvent.GET_PRE_SIGNATURE)
                                .to(MediaState.PREPARE_PUBLISH_MEDIA)
                                .action(mediaAction::initialOnGetPreSignatureToPreparePublishMedia)
                                .build()
                        ,
                        MediaState.EXCEED_LIMIT,
                        MediaRule.builder()
                                .from(MediaState.INITIAL)
                                .on(MediaEvent.GET_PRE_SIGNATURE)
                                .to(MediaState.EXCEED_LIMIT)
                                .action(mediaAction::initialOnGetPreSignatureToExceedLimit)
                                .build()
                )
        );
        rules.put(MediaState.PREPARE_PUBLISH_MEDIA + ":" + MediaEvent.START_PROCESSING,
                Map.of(
                        MediaState.PROCESSING,
                        MediaRule.builder()
                                .from(MediaState.PREPARE_PUBLISH_MEDIA)
                                .on(MediaEvent.START_PROCESSING)
                                .to(MediaState.PROCESSING)
                                .action(mediaAction::preparePublishMediaOnStartProcessingToProcessing)
                                .build()
                )
        );
        rules.put(MediaState.PROCESSING + ":" + MediaEvent.FINISH_DECODING,
                Map.of(
                        MediaState.DECODE_FINISH,
                        MediaRule.builder()
                                .from(MediaState.PROCESSING)
                                .on(MediaEvent.FINISH_DECODING)
                                .to(MediaState.DECODE_FINISH)
                                .action(mediaAction::processingOnFinishDecodingToDecodeFinish)
                                .build()
                )
        );
        rules.put(MediaState.PROCESSING + ":" + MediaEvent.FINISH_EXTRACTION,
                Map.of(
                        MediaState.EXTRACT_FINISH,
                        MediaRule.builder()
                                .from(MediaState.PROCESSING)
                                .on(MediaEvent.FINISH_EXTRACTION)
                                .to(MediaState.EXTRACT_FINISH)
                                .action(mediaAction::processingOnFinishExtractToExtractFinish)
                                .build()
                )
        );
        rules.put(MediaState.EXTRACT_FINISH + ":" + MediaEvent.FINISH_DECODING,
                Map.of(
                        MediaState.FINISHED,
                        MediaRule.builder()
                                .from(MediaState.EXTRACT_FINISH)
                                .on(MediaEvent.FINISH_DECODING)
                                .to(MediaState.FINISHED)
                                .action(mediaAction::extractFinishOnFinishDecodeToFinished)
                                .build()
                )
        );
        rules.put(MediaState.DECODE_FINISH + ":" + MediaEvent.FINISH_EXTRACTION,
                Map.of(
                        MediaState.FINISHED,
                        MediaRule.builder()
                                .from(MediaState.DECODE_FINISH)
                                .on(MediaEvent.FINISH_EXTRACTION)
                                .to(MediaState.FINISHED)
                                .action(mediaAction::decodeFinishOnFinishExtractToFinished)
                                .build()
                )
        );
    }

    public MediaState sendEvent(@NotNull MediaEventContext authEventContext, @NotNull MediaEvent event) {
        Long id = authEventContext.getId();
        String userId = authEventContext.getString("userId") != null ? authEventContext.getString("userId") : "";
        List<String> args = List.of(id.toString(), event.toString(), userId);

        String res = executeFcallToString(args);

        int lastColonIndex = res.lastIndexOf(':');
        String eventKey = res.substring(0, lastColonIndex);
        String newState = res.substring(lastColonIndex + 1);

        if (eventKey.equals("ERROR:INVALID_TRANSITION")) return MediaState.ERROR;
        MediaRule mediaRule = rules.get(eventKey).get(MediaState.valueOf(newState));
        if(mediaRule == null) return MediaState.valueOf(newState);
        mediaRule.getAction().accept(authEventContext);
        return MediaState.valueOf(newState);
    }

    /**
     * 执行 Redis FCALL 命令，并将返回值强制转换为字符串。
     * 支持 Redis 返回 byte[]、List<byte[]>（取第一个元素）等情况。
     *
     * @param args 传递给函数的参数列表（不包含 numkeys）
     * @return Redis 返回的字符串（例如 "INITIAL:REGISTER_SEND_CODE:PENDING"）
     */
    private String executeFcallToString(List<String> args) {
        return stringRedisTemplate.execute((RedisCallback<String>) connection -> {
            // 构建参数：FCALL function_name numkeys [arg1 arg2 ...]
            List<byte[]> paramList = new ArrayList<>();
            paramList.add("process_event".getBytes(StandardCharsets.UTF_8));
            paramList.add("0".getBytes(StandardCharsets.UTF_8)); // numkeys = 0
            for (String arg : args) {
                paramList.add(arg.getBytes(StandardCharsets.UTF_8));
            }
            byte[][] paramArray = paramList.toArray(new byte[0][]);

            Object response = connection.execute("FCALL", paramArray);

            switch (response) {
                case null -> throw new RuntimeException("FCALL 返回 null");
                // 根据实际类型提取字符串
                case byte[] bytes -> {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                default -> throw new RuntimeException("FCALL 返回不支持的类型: " + response.getClass());
            }

        });
    }
}