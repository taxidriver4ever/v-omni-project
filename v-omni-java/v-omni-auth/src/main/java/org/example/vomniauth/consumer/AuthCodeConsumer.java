package org.example.vomniauth.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomniauth.domain.statemachine.AuthState;
import org.example.vomniauth.dto.BasicInfoDto;
import org.example.vomniauth.dto.IdAndEmailDto;
import org.example.vomniauth.mapper.UserMapper;
import org.example.vomniauth.po.UserPo;
import org.example.vomniauth.service.DocumentUserProfileService;
import org.example.vomniauth.service.MailService;
import org.example.vomniauth.util.SnowflakeIdWorker;
import org.example.vomniauth.util.UsernameGenerator;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class AuthCodeConsumer {

    private static final String htmlFormat =
            """
            <div style="background-color: #f6f9fc; padding: 50px 0; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">
                <div style="max-width: 500px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 10px 30px rgba(0,0,0,0.05);">
                    <div style="background: linear-gradient(135deg, #4A90E2 0%%, #7E57C2 100%%); height: 6px;"></div>
            
                    <div style="padding: 40px;">
                        <h2 style="margin: 0 0 20px; color: #1a1f36; font-size: 24px; font-weight: 600; text-align: center;">
                            V-Omni <span style="color: #4A90E2;">验证中心</span>
                        </h2>
            
                        <p style="margin: 0 0 30px; color: #4f566b; font-size: 16px; line-height: 1.6; text-align: center;">
                            您好！感谢注册 V-Omni 平台。请在验证页面输入以下验证码以完成操作：
                        </p>
            
                        <div style="background-color: #f8fafd; border: 1px dashed #adc6e8; border-radius: 8px; padding: 25px; text-align: center; margin-bottom: 30px;">
                            <span style="display: block; font-family: 'Courier New', Courier, monospace; font-size: 36px; font-weight: bold; color: #2e3a59; letter-spacing: 8px; text-shadow: 1px 1px 0 #fff;">
                                %s
                            </span>
                        </div>
            
                        <p style="margin: 0; color: #727f94; font-size: 13px; text-align: center;">
                            ⏳ 该验证码将在 <strong style="color: #ed5f74;">5 分钟</strong> 后失效
                        </p>
                    </div>
            
                    <div style="background-color: #fbfbfb; padding: 20px; border-top: 1px solid #eeeeee; text-align: center;">
                        <p style="margin: 0; color: #a3acb9; font-size: 12px;">
                            此邮件由系统自动发出，请勿直接回复。<br>
                            © 2026 V-Omni Project. All rights reserved.
                        </p>
                    </div>
                </div>
            </div>
            """;

    @Resource
    private MailService mailService;

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private RedisTemplate<String,Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RBloomFilter<String> emailBloomFilter;

    @Resource
    private DocumentUserProfileService documentUserProfileService;

    private final static int REGISTERED_TTL = 60 * 60;

    private final static int VERIFICATION_EXPIRE_TIME = 60 * 5;

    @KafkaListener(topics = "auth-code-topic", groupId = "v-omni-auth-group")
    public void authCodeTopicConsume(@NotNull IdAndEmailDto idAndEmailDto) {
        String id = idAndEmailDto.getId();
        String email = idAndEmailDto.getEmail();
        log.info("注册收到 Kafka 邮件任务: {}", email);

        StringBuilder code = new StringBuilder();
        Random r = new Random();
        for(int i = 0;i<6;i++)
            code.append(r.nextInt(10));

        String htmlContent = htmlFormat.formatted(code);
        try {
            // 执行发信
            stringRedisTemplate.opsForValue().set(
                    "register:code:id:" + id,
                    code.toString(),
                    VERIFICATION_EXPIRE_TIME,
                    TimeUnit.SECONDS
            );
            mailService.sendHtmlMail(email, "【V-Omni】注册验证码", htmlContent);
            log.info("注册邮件发送成功: {}", email);
        } catch (Exception e) {
            log.error("注册邮件发送彻底失败，开始回滚 Redis 状态: {}", email);

            // 如果你用了布隆过滤器，注意：布隆过滤器无法删除
            // 这就是为什么我们在 processAuthCode 里要查数据库做二次校验的原因
        }
    }

    @KafkaListener(topics = "login-code-topic", groupId = "v-omni-auth-group")
    public void loginCodeTopicConsume(@NotNull IdAndEmailDto idAndEmailDto) {
        String id = idAndEmailDto.getId();
        String email = idAndEmailDto.getEmail();
        log.info("登录收到 Kafka 邮件任务: {}", email);

        StringBuilder code = new StringBuilder();
        Random r = new Random();
        for(int i = 0;i<6;i++)
            code.append(r.nextInt(10));

        String htmlContent = htmlFormat.formatted(code);
        try {
            // 执行发信
            stringRedisTemplate.opsForValue().set(
                    "login:code:id:" + id,
                    code.toString(),
                    VERIFICATION_EXPIRE_TIME,
                    TimeUnit.SECONDS
            );
            mailService.sendHtmlMail(email, "【V-Omni】登录验证码", htmlContent);
            log.info("登录邮件发送成功: {}", email);
        } catch (Exception e) {
            log.error("登录邮件发送彻底失败，开始回滚 Redis 状态: {}", email);

            // 如果你用了布隆过滤器，注意：布隆过滤器无法删除
            // 这就是为什么我们在 processAuthCode 里要查数据库做二次校验的原因
        }
    }

    @Transactional
    @KafkaListener(topics = "input-user-information-topic", groupId = "v-omni-auth-group")
    public void inputUserInformationTopicConsume(@NotNull IdAndEmailDto idAndEmailDto) {
        String idString = idAndEmailDto.getId();
        String email = idAndEmailDto.getEmail();
        Long id = Long.valueOf(idString);
        String username = UsernameGenerator.generateRandomName();
        UserPo user = new UserPo(id,username,email,AuthState.REGISTERED);
        user.setAvatarPath("default-avatar.png");
        Date now = new Date();
        user.setCreateTime(now);
        user.setUpdateTime(now);

        int i = userMapper.insertUser(user);
        documentUserProfileService.createProfileOnRegistration(idString,now);

        if(i == 0)
            log.info("邮箱{}sql插入错误", email);
        else if(i == 1) {
            stringRedisTemplate.opsForValue().set(
                    "auth:state:id:" + idString,
                    AuthState.REGISTERED.toString(),
                    REGISTERED_TTL,
                    TimeUnit.SECONDS
            );
            emailBloomFilter.add(email);
        }
    }

    @Transactional
    @KafkaListener(topics = "auth-basic-info",groupId = "v-omni-auth-group")
    public void authBasicInfoTopicConsume(@NotNull BasicInfoDto basicInfoDto) {
        Long userId = basicInfoDto.getUserId();
        Integer sex = basicInfoDto.getSex();
        String country = basicInfoDto.getCountry();
        String province = basicInfoDto.getProvince();
        String city = basicInfoDto.getCity();
        Integer birthYear = basicInfoDto.getBirthYear();

        userMapper.updateUserBasicInfo(basicInfoDto);
        documentUserProfileService.updateUserDemographics(String.valueOf(userId),sex,birthYear,country,province,city);
    }
}
