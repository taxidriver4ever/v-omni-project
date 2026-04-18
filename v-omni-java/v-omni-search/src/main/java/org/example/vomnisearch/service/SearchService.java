package org.example.vomnisearch.service;

import org.example.vomnisearch.dto.UserContent;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface SearchService {
    List<String> searchVideo(UserContent userContent) throws Exception;
}
