package org.example.vomnimedia;

import io.jsonwebtoken.Claims;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import jakarta.annotation.Resource;
import org.example.vomnimedia.service.MinioService;
import org.example.vomnimedia.util.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@SpringBootTest
class VOmniMediaApplicationTests {

    @Resource
    private MinioService minioService;

    @Resource
    private JwtUtils jwtUtils;

    @Test
    void contextLoads() throws Exception {
        String s = jwtUtils.generateAccessToken("170452383233609728");
        System.out.println(s);
    }

}
