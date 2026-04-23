package org.example.vomnimedia.domain.statemachine;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.dto.AvatarAndAuthorDto;
import org.example.vomnimedia.dto.PreparePublishToMediaDto;
import org.example.vomnimedia.mapper.MediaMapper;
import org.example.vomnimedia.mapper.UserMapper;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.po.MediaPo;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.DocumentVectorMediaService;
import org.example.vomnimedia.service.impl.MediaServiceImpl;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class MediaAction {

    @Resource
    private MediaMapper mediaMapper;

    @Resource
    private MinioService minioService;

    @Resource
    private RedisTemplate<String,byte[]> byteRedisTemplate;

    @Resource
    private DocumentVectorMediaService documentVectorMediaService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Resource
    @Lazy
    private MediaAction self;

    @Resource
    private KafkaTemplate<String,PreparePublishToMediaDto> kafkaTemplate;


    public void initialOnGetPreSignatureToPreparePublishMedia(@NotNull MediaEventContext mediaEventContext) {
        try {
            Long id = mediaEventContext.getId();
            String rawsVideoUploadUrl = minioService.getRawsVideoUploadUrl(id.toString());
            mediaEventContext.with("preSign", rawsVideoUploadUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void initialOnGetPreSignatureToExceedLimit(@NotNull MediaEventContext mediaEventContext) {
        String userId = mediaEventContext.getString("userId");
        log.info("{}发送注册验证码尝试次数过多", userId);
    }

    public void preparePublishMediaOnStartProcessingToProcessing(@NotNull MediaEventContext mediaEventContext) {
        Long id = mediaEventContext.getId();
        log.info("{}开始加工",id);
    }

    public void processingOnFinishDecodingToDecodeFinish(@NotNull MediaEventContext mediaEventContext) {
    }

    public void processingOnFinishExtractToExtractFinish(@NotNull MediaEventContext mediaEventContext) {
    }

    public void decodeFinishOnFinishExtractToFinished(@NotNull MediaEventContext mediaEventContext) {
        prepareAndSave(mediaEventContext);
    }

    public void extractFinishOnFinishDecodeToFinished(@NotNull MediaEventContext mediaEventContext) {
        prepareAndSave(mediaEventContext);
    }

    private void prepareAndSave(@NotNull MediaEventContext mediaEventContext) {
        Long id = mediaEventContext.getId();
        String userId = mediaEventContext.getString("userId");
        String title = mediaEventContext.getString("title");
        String idStr = String.valueOf(id);

        // 1. 同时获取两路向量 (耗时 IO)
        float[] videoVectorRaw = getVideoVectorFromRedis(idStr);
        float[] titleVectorRaw = getTitleVectorFromRedis(idStr);

        // 封面路径获取
        String coverPath = stringRedisTemplate.opsForValue().get("media:vector:id:" + id);

        // 只要有一路向量缺失，就无法满足双塔检索需求
        if (videoVectorRaw == null || titleVectorRaw == null) {
            log.warn("向量缺失 [视频: {}, 标题: {}]，放弃存储。ID: {}",
                    videoVectorRaw != null, titleVectorRaw != null, id);
            return;
        }

        // 2. 耗时计算：转换格式 (放在事务外)
        List<Float> videoVector = convertToList(videoVectorRaw);
        List<Float> titleVector = convertToList(titleVectorRaw);

        // 3. 调用代理方法执行事务
        try {
            self.executePersistentTask(id, userId, title, videoVector, titleVector, coverPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 提取一个通用的转换方法
    private List<Float> convertToList(float[] floats) {
        List<Float> list = new ArrayList<>(floats.length);
        for (float v : floats) {
            list.add(v);
        }
        return list;
    }
    /**
     * 核心持久化方法：MySQL 和 ES 放在一起，保证原子性
     * 只有此方法开启事务，缩短数据库连接占用时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void executePersistentTask(Long id, String userId, String title,
                                      List<Float> videoVector,
                                      List<Float> titleVector,
                                      String coverPath) throws IOException {

        // 1. MySQL 操作：查询作者
        AvatarAndAuthorDto avatarAndAuthorDto = userMapper.selectAvatarAndAuthorByUserId(Long.parseLong(userId));
        if (avatarAndAuthorDto == null) {
            throw new RuntimeException("未找到对应的作者信息，用户ID: " + userId);
        }

        String author = avatarAndAuthorDto.getAuthor();
        String avatar = avatarAndAuthorDto.getAvatar();
        Date updateDate = new Date();

        // 2. 构造 ES PO 对象 (对应你新的双向量 Mapping)
        DocumentVectorMediaPo documentVectorMediaPo = new DocumentVectorMediaPo();
        documentVectorMediaPo.setId(String.valueOf(id));
        documentVectorMediaPo.setTitle(title);
        documentVectorMediaPo.setAuthor(author);
        documentVectorMediaPo.setAvatarPath(avatar);
        documentVectorMediaPo.setCoverPath(coverPath);
        documentVectorMediaPo.setLikeCount(0);
        documentVectorMediaPo.setCollectionCount(0);
        documentVectorMediaPo.setCommentCount(0);
        documentVectorMediaPo.setUserId(userId);
        documentVectorMediaPo.setDeleted(false);
        documentVectorMediaPo.setCreateTime(updateDate);
        documentVectorMediaPo.setUpdateTime(updateDate);

        // 设置双向量字段
        documentVectorMediaPo.setVideoEmbedding(videoVector); // 对应 video_embedding
        documentVectorMediaPo.setTextEmbedding(titleVector);  // 对应 text_embedding

        // 3. MySQL 写入逻辑
        MediaPo mediaPo = new MediaPo();
        mediaPo.setId(id);
        mediaPo.setTitle(title);
        mediaPo.setCoverPath(coverPath);
        mediaPo.setCreateTime(updateDate); // 别忘了 createTime
        mediaPo.setUpdateTime(updateDate);
        mediaPo.setState(MediaState.FINISHED.toString());
        mediaPo.setUserId(Long.parseLong(userId));


        mediaMapper.insertUser(mediaPo); // 确保你的 Mapper 能够处理插入
        documentVectorMediaService.upsert(documentVectorMediaPo);
    }

    private float[] getVideoVectorFromRedis(@NotNull String id) {
        byte[] data = byteRedisTemplate.opsForValue().get("media:video:vector:id:" + id);
        float[] vector = null;
        if (data != null) {
            vector = new float[data.length / 4];
            ByteBuffer.wrap(data).asFloatBuffer().get(vector);
        }
        return vector;
    }

    private float[] getTitleVectorFromRedis(@NotNull String id) {
        byte[] data = byteRedisTemplate.opsForValue().get("media:title:vector:id:" + id);
        float[] vector = null;
        if (data != null) {
            vector = new float[data.length / 4];
            ByteBuffer.wrap(data).asFloatBuffer().get(vector);
        }
        return vector;
    }
}
