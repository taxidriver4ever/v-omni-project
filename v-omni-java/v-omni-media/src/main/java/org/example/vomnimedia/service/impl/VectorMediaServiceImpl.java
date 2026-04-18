package org.example.vomnimedia.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.example.vomnimedia.po.DocumentVectorMediaPo;
import org.example.vomnimedia.service.VectorMediaService;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class VectorMediaServiceImpl implements VectorMediaService {

    private final ElasticsearchClient client;

    private static final String INDEX = "vector_media_index";

    /**
     * ① 全量写入（第一次进入 ES 用）
     */
    @Override
    public void upsert(DocumentVectorMediaPo doc) throws IOException {

        try {
            client.index(i -> i
                    .index(INDEX)
                    .id(doc.getId())
                    .document(doc)
            );

//            System.out.println("====== ES写入结果 ======");
//            System.out.println("index = " + INDEX);
//            System.out.println("id = " + response.id());
//            System.out.println("result = " + response.result());
//            System.out.println("=======================");

        } catch (Exception e) {
            System.out.println("====== ES写入失败 ======");
            e.printStackTrace();
            System.out.println("=======================");
        }
    }

    /**
     * ② 局部更新（推荐使用）
     */
    @Override
    public void updateFields(String id, Map<String, Object> fields) throws IOException {

        if (fields == null || fields.isEmpty()) return;

        client.update(u -> u
                        .index(INDEX)
                        .id(id)
                        .doc(fields),
                Object.class
        );
    }



}
