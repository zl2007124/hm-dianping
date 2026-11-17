package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metric;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

 /** 启动时把店铺坐标导入 Redis GEO（key = shop:geo:{typeId}，member = 店铺id） */
 @PostConstruct
 public void loadShopGeo() {
  List<Shop> shops = list();
  if (shops.isEmpty()) {
   return;
  }
  for (Shop shop : shops) {
   if (shop.getX() == null || shop.getY() == null) {
    continue;
   }
   stringRedisTemplate.opsForGeo().add(
     SHOP_GEO_KEY + shop.getTypeId(),
     new Point(shop.getX(), shop.getY()),
     shop.getId().toString());
  }
  log.info("店铺坐标已导入 Redis GEO，共 {} 条", shops.size());
 }

    @Override
    public Result queryById(Long id) {
        //方案一：逻辑过期（需要预热）
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        //方案二：空值缓存防穿透（不需要预热，二选一）
        //Shop shop = cacheClient.queryWithPassThrough(
        //        CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 预热：查库后写入带逻辑过期时间的缓存（讲义里用单元测试手动调用）
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + id, getById(id), expireSeconds, TimeUnit.SECONDS);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.先更新数据库
        updateById(shop);
        //2.再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
       //判断是否根据坐标查询
        if(x==null||y==null)
        {
            // 根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
 GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
 .radius(key, new Circle(new Point(x, y), new Distance(5000)),
 RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
 .includeDistance().sortAscending().limit(end));
 if (results == null || results.getContent().isEmpty()) {
 return Result.ok(Collections.emptyList());
 }
 //截取 from 到 end 部分，同时收集 id 与距离
 List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
 List<Long> ids = new ArrayList<>(list.size());
 Map<String, Distance> distanceMap = new HashMap<>();
 list.stream().skip(from).forEach(result -> {
 String shopIdStr = result.getContent().getName();
 ids.add(Long.valueOf(shopIdStr));
 distanceMap.put(shopIdStr, result.getDistance());
 });
 if (ids.isEmpty()) {
 return Result.ok(Collections.emptyList());
 }
 //根据id查询shop，用 ORDER BY FIELD 保持距离顺序
 String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
 List<Shop> shops = query().in("id", ids)
 .last("ORDER BY FIELD(id," + idStr + ")").list();
 //把距离填回店铺
 for (Shop shop : shops) {
 Distance distance = distanceMap.get(shop.getId().toString());
 if (distance != null) {
 shop.setDistance(distance.getValue());
 }
 }
 return Result.ok(shops);
 }
}
