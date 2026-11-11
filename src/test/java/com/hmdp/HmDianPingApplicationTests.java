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

    @Resource
    private com.hmdp.utils.RedisIdWorker redisIdWorker;

    /**
     * 全局唯一 ID：300 线程 × 100 个 = 30000 个，检查是否重复
     */
    @Test
    void testRedisIdWorker() throws InterruptedException {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(300);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(300);
        java.util.Set<Long> ids = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    ids.add(redisIdWorker.nextId("order"));
                }
                latch.countDown();
            });
        }
        latch.await();
        executor.shutdown();
        System.out.println("耗时：" + (System.currentTimeMillis() - begin) + " ms，去重后数量：" + ids.size() + "（期望 30000）");
    }

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
