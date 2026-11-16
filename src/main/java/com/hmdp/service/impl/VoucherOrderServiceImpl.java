package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 秒杀订单消息队列的 stream 名称与消费组 */
    private static final String QUEUE_NAME = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 注入自己：拿到的是代理对象，异步线程里调用也能让事务生效 */
    @Resource
    private IVoucherOrderService self;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /** 秒杀资格校验脚本：0=成功 1=库存不足 2=重复下单 */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    /** 建消费组脚本（MKSTREAM：stream 不存在时一起创建） */
    private static final DefaultRedisScript<Long> CREATE_GROUP_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        CREATE_GROUP_SCRIPT = new DefaultRedisScript<>();
        CREATE_GROUP_SCRIPT.setScriptText(
                "return redis.call('XGROUP','CREATE',KEYS[1],ARGV[1],'0','MKSTREAM')");
        CREATE_GROUP_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        //确保消费组存在，否则消费时抛 NOGROUP
        try {
            stringRedisTemplate.execute(CREATE_GROUP_SCRIPT,
                    Collections.singletonList(QUEUE_NAME), GROUP_NAME);
            log.info("消费组已创建：{}/{}", QUEUE_NAME, GROUP_NAME);
        } catch (Exception e) {
            //BUSYGROUP：已存在，正常
            log.debug("消费组已存在或创建失败：{}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.读取 stream 中未消费的消息（阻塞 2 秒，避免空转）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed()));
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    //2.处理订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //3.ACK 确认，处理成功才确认
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    //捕获所有异常，避免线程退出后订单不再被处理
                    log.error("处理订单异常", e);
                    //处理失败的消息留在 pending-list 里，稍后补偿
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        int failCount = 0;
        while (true) {
            try {
                //从 pending-list 读未确认的消息（offset 0 表示从头读）
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0")));
                if (list == null || list.isEmpty()) {
                    return;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                //ACK 确认
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                failCount = 0;
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                //连续失败多次就退出本轮，等下一轮再补偿，避免死循环刷日志
                if (++failCount >= 3) {
                    log.error("pending-list 连续处理失败，退出本轮补偿");
                    return;
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //加锁：同一用户串行，防重复下单
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(10);
        if (!isLock) {
            log.error("不允许重复下单，userId={}", userId);
            return;
        }
        try {
            //通过代理调用，事务才生效
            self.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.获取用户
        Long userId = UserHolder.getUser().getId();
        //2.生成订单 id
        long orderId = redisIdWorker.nextId("order");
        //3.执行 lua 脚本：判断库存、判断重复、扣库存、记用户、投递消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        if (result == null) {
            return Result.fail("秒杀失败，请稍后重试");
        }
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //4.有资格，消息已由 lua 写入 stream，直接返回订单 id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //异步线程里没有 ThreadLocal，用户信息从订单对象取
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //一人一单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count >= 1) {
            log.error("用户已购买过该优惠券，userId={}", userId);
            return;
        }
        //扣减库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足，voucherId={}", voucherId);
            return;
        }
        //创建订单
        save(voucherOrder);
    }
}
