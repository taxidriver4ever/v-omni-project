package org.example.vomniinteract.service;

public interface InteractService {
    Long doLike(String mediaId);
    Long cancelLike(String mediaId);
    Long doCollection(String mediaId);
    Long cancelCollection(String mediaId);
}
