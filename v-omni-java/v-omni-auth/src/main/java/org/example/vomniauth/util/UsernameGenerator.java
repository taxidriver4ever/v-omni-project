package org.example.vomniauth.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class UsernameGenerator {
    private static final String[] ADJECTIVES = {"深邃", "极光", "赛博", "星空", "灵动", "硬核", "沉默", "虚空"};
    private static final String[] NOUNS = {"极客", "智能体", "观察者", "开发者", "漫游者", "终端", "核心", "守望者"};

    public static String generateRandomName() {
        int adjIndex = ThreadLocalRandom.current().nextInt(ADJECTIVES.length);
        int nounIndex = ThreadLocalRandom.current().nextInt(NOUNS.length);

        // 拼接后再加 4 位随机数，彻底避免碰撞
        int randomNum = ThreadLocalRandom.current().nextInt(1000, 10000);

        return ADJECTIVES[adjIndex] + "_" + NOUNS[nounIndex] + randomNum;
    }
}
