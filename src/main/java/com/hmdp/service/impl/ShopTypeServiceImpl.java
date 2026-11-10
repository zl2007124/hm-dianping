package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //从redis查询
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        //若查到直接返回（JSON 数组 -> List<ShopType>）
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, ShopType.class);
        }

        //未查到去查数据库，按 sort 升序
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        //库里也没有，返回空列表，不写缓存（避免脏数据占住缓存 1 小时）
        if (shopTypes == null || shopTypes.isEmpty()) {
            return shopTypes;
        }

        //存入redis，1 小时过期
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypes),
                        CACHE_SHOP_TYPE_TTL, TimeUnit.HOURS);

        return shopTypes;
    }
}
