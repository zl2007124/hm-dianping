package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //缓存重建线程池（逻辑过期方案异步重建用）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //互斥锁解决缓存击穿
        //return queryWithMutex(id);

        //逻辑过期解决缓存击穿
        return queryWithLogicalExpire(id);
    }
    private boolean tryLock(String key) {
       Boolean flag =  stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10,TimeUnit.SECONDS);
     return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    private Result queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //1.1 命中真实数据
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //1.2 命中空值缓存
        if (shopJson != null) {
            return Result.fail("店铺不存在");
        }

        //2.未命中：抢互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //2.1 没抢到锁：休眠一会儿再重试（注意：必须在 try 之前返回，否则 finally 会把别人的锁删掉）
        if (!isLock) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryWithMutex(id);
        }
        try {
            //3.抢到锁：二次校验缓存（可能别人刚重建完）
            String again = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(again)) {
                return Result.ok(JSONUtil.toBean(again, Shop.class));
            }
            if (again != null) {
                return Result.fail("店铺不存在");
            }
            //4.查数据库
            Shop shop = getById(id);
            if (shop == null) {
                //4.1 库里也没有：写空值缓存，2 分钟
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            //4.2 有数据：写缓存，TTL 加 0~10 分钟随机（顺带防雪崩）
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(shop),
                            CACHE_SHOP_TTL + RandomUtil.randomLong(0, 10), TimeUnit.MINUTES);
            return Result.ok(shop);
        } finally {
            //5.无论成功失败都释放锁
            unlock(lockKey);
        }
    }
   public void saveShop2Redis(Long id,Long expireSeconds)
    {
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    /**
     * 逻辑过期方式解决缓存击穿
     */
    private Result queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.查缓存（逻辑过期方案依赖提前预热，没缓存直接认为不存在）
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return Result.fail("店铺不存在");
        }
        //2.反序列化：先拿 RedisData，再取 Shop
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //3.未过期：直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return Result.ok(shop);
        }
        //4.已过期：抢锁，抢到就开独立线程异步重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 20L);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //5.无论重建成没成，先返回旧数据
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null)
        {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
