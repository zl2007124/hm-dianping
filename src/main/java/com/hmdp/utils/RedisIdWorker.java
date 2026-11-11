package com.hmdp.utils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1794407707;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public long nextId(String keyPrefix)
    {
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowsecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowsecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //3.拼接返回
        return 0l;
    }
    public static void main(String[] args)
    {
        LocalDateTime time = LocalDateTime.now();
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}