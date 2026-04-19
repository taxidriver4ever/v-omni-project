package org.example.vomniinteract.service;

public interface InteractService {
    Long doLike(Long mediaId,Long userId);
    Long cancelLike(Long mediaId,Long userId);
}
