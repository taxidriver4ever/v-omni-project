package org.example.vomniinteract.controller;

import org.example.vomniinteract.common.MyResult;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(maxAge = 3600)
@RestController
@RequestMapping("/interact")
public class InteractController {

    @PostMapping("/do/like")
    public MyResult<String> doLike() {
        return MyResult.success();
    }

    @PostMapping("/cancel/like")
    public MyResult<String> cancelLike() {
        return MyResult.success();
    }
}
