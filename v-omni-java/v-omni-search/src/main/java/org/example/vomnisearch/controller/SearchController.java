package org.example.vomnisearch.controller;

import jakarta.annotation.Resource;
import org.example.vomnisearch.common.MyResult;
import org.example.vomnisearch.dto.UserContent;
import org.example.vomnisearch.po.DocumentVectorMediaPo;
import org.example.vomnisearch.service.EmbeddingService;
import org.example.vomnisearch.service.MinioService;
import org.example.vomnisearch.service.VectorMediaService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private VectorMediaService vectorMediaService;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private MinioService minioService;

    @PostMapping("/hybrid/video")
    public MyResult<List<String>> searchVideo(@RequestBody UserContent userContent) throws Exception {
        String content = userContent.getContent();
//        long start = System.currentTimeMillis();
        float[][] vector = embeddingService.getVector(content);
//        System.out.println("TEI耗时: " + (System.currentTimeMillis() - start));
        List<String> strings = vectorMediaService.hybridSearchIds(content, vector[0], content, 10);
//        System.out.println("混合搜索耗时: " + (System.currentTimeMillis() - start));
        if(strings == null) return MyResult.error(404,"获取视频失败");
        List<String> results = new ArrayList<>();
        for (String string : strings) {
            String s = minioService.generateHlsPlaybackUrl(string);
            results.add(s);
        }
//        System.out.println("转换url耗时: " + (System.currentTimeMillis() - start));
//        List<DocumentVectorMediaPo> documentVectorMediaPos = vectorMediaService.hybridSearch(content, vector[0], content, 10);
//        if(documentVectorMediaPos == null) return MyResult.error(404,"获取视频失败");
//        List<String> results = new ArrayList<>();
//        for (DocumentVectorMediaPo documentVectorMediaPo : documentVectorMediaPos) {
//            System.out.println(documentVectorMediaPo.getTitle() + "   " + documentVectorMediaPo.getAuthor());
//            String url = minioService.generateHlsPlaybackUrl(documentVectorMediaPo.getId());
//            results.add(url);
//        }
        return MyResult.success(results);
    }
}
