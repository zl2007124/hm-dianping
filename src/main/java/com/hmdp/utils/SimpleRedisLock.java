package com.hmdp.utils;
import com.hmdp.utils.ILock;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock {
 private String name ;

 private StringRedisTemplate stringRedisTemplate;

 public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
  this.name = name;
  this.stringRedisTemplate = stringRedisTemplate;
 }

 private static final String KEY_PREFIX = "lock:";

 private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
 @Override
 public boolean tryLock(long timeoutSec) {

  String threadId =ID_PREFIX +  Thread.currentThread().getId();

  Boolean success = stringRedisTemplate.opsForValue()
          .setIfAbsent(KEY_PREFIX + name,  threadId, timeoutSec, TimeUnit.SECONDS);
  return Boolean.TRUE.equals(success);
 }
 @Override
 public void unlock() {
  //获取线程
  String threadId =ID_PREFIX +  Thread.currentThread().getId();
  //获取锁标识
  String currentValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
  //判断是否一致
  if(threadId.equals(currentValue)){
   //释放
   stringRedisTemplate.delete(KEY_PREFIX + name);
  }
 }
}