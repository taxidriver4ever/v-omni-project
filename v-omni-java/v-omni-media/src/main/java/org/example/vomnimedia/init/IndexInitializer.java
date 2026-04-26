package org.example.vomnimedia.init;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class IndexInitializer {

    private final ElasticsearchClient client;

    @PostConstruct
    public void init() throws IOException {

        String indexName = "vector_media_index";

        boolean exists = client.indices()
                .exists(e -> e.index(indexName))
                .value();

        if (!exists) {
            client.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                            // title
                            .properties("title", p -> p
                                    .text(t -> t
                                            .analyzer("ik_max_word")      // 索引时：细粒度切分
                                            .searchAnalyzer("ik_smart")    // 搜索时：粗粒度切分
                                    )
                            )
                            // author
                            .properties("author", p -> p
                                    .keyword(k -> k)
                            )
                            .properties("url", p -> p
                                    .keyword(k -> k))
                            // embedding 向量字段
                            .properties("embedding", p -> p
                                    .denseVector(d -> d
                                            .dims(1024)
                                            .index(true)
                                            .similarity("cosine")
                                            .indexOptions(io -> io
                                                    .type("int8_hnsw")
                                                    .m(8)              // 每个节点的邻居数，默认 16，越大越准越慢
                                                    .efConstruction(64) // 构建时的搜索深度，默认 100
                                            )
                                    )
                            )

                    )
            );
        }
    }
}