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
                                    .text(t -> t)
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
                                            .dims(2048)   // ⚠️ 根据你的模型改
                                            .index(true)
                                            .similarity("cosine")
                                    )
                            )
                    )
            );
        }
    }
}