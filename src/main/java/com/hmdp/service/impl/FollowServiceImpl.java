package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

 @Override
 public Result isFollow(Long followUserId) {
  Long userId = UserHolder.getUser().getId();
  Integer count = query().eq("user_id", userId)
    .eq("follow_user_id", followUserId).count();
  return Result.ok(count > 0);
 }

 @Override
 public Result follow(Long followUserId, Boolean isFollow) {
  Long userId = UserHolder.getUser().getId();
  //不能关注自己
  if (userId.equals(followUserId)) {
   return Result.fail("不能关注自己");
  }
  if (isFollow) {
   //已经关注过就不再插入，避免重复数据
   int count = query().eq("user_id", userId)
     .eq("follow_user_id", followUserId).count();
   if (count > 0) {
    return Result.ok();
   }
   Follow follow = new Follow();
   follow.setUserId(userId);
   follow.setFollowUserId(followUserId);
   save(follow);
  } else {
   remove(new QueryWrapper<Follow>().eq("user_id", userId)
     .eq("follow_user_id", followUserId));
  }
  return Result.ok();
 }
}
