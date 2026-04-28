package org.example.vomnimedia;

import io.jsonwebtoken.Claims;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.service.VectorService;
import org.example.vomnimedia.util.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Set;

@Slf4j
@SpringBootTest
class VOmniMediaApplicationTests {

    @Resource
    private MinioService minioService;

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private VectorService vectorService;


    @Test
    void contextLoads() throws Exception {
//        float[] textVector = vectorService.getTextVector("你好");
//        for (int i = 0; i < textVector.length; i++) {
//            System.out.print(textVector[i] + " ");
//        }
// 1. 打印当前 Java 找 DLL 的路径，看看到底有没有你的 CUDA 目录
//        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
//        try {
//            options.addCUDA(0);
//            System.out.println("🚀 强制启用 CUDA 成功！别管那个列表了，直接跑就行。");
//        } catch (Exception e) {
//            System.err.println("❌ 强制启用失败，报错提示: " + e.getMessage());
//        }
        String s = jwtUtils.generateAccessToken("170452383233609728");
        System.out.println(s);
    }

}
