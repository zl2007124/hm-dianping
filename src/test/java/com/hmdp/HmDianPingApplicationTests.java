package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 逻辑过期方案需要预热：查库后把带逻辑过期时间的数据写进 Redis
     */
    @Test
    void testSaveShop2Redis() {
        ((ShopServiceImpl) shopService).saveShop2Redis(1L, 20L);
        String json = stringRedisTemplate.opsForValue().get("cache:shop:1");
        System.out.println("预热完成，缓存内容前 120 字：" + (json == null ? "null" : json.substring(0, Math.min(120, json.length()))));
    }
}
