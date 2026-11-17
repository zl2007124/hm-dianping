package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

 @Resource
 private IUserService userService;

 @Resource
 private StringRedisTemplate stringRedisTemplate;

 @Resource
 private IFollowService followService ;


 /** 点赞记录的 Redis key 前缀，结构为 ZSet：member=用户id，score=点赞时间戳 */
 private static final String LIKED_KEY = "blog:liked:";

 /** 补全笔记的博主信息（name、icon 不在 tb_blog 表里） */
 private void queryBlogUser(Blog blog) {
  User user = userService.getById(blog.getUserId());
  if (user == null) {
   return;
  }
  blog.setName(user.getNickName());
  blog.setIcon(user.getIcon());
 }

 /** 判断当前登录用户是否点赞过这篇笔记 */
 private void isBlogLiked(Blog blog) {
  UserDTO user = UserHolder.getUser();
  if (user == null) {
   //未登录时不需要判断
   return;
  }
  Double score = stringRedisTemplate.opsForZSet()
    .score(LIKED_KEY + blog.getId(), user.getId().toString());
  blog.setIsLike(score != null);
 }

 @Override
 public Result queryHotBlog(Integer current) {
  // 根据点赞数排序分页查询
  Page<Blog> page = query()
    .orderByDesc("liked")
    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
  List<Blog> records = page.getRecords();
  // 补博主信息和点赞状态
  records.forEach(blog -> {
   queryBlogUser(blog);
   isBlogLiked(blog);
  });
  return Result.ok(records);
 }

 @Override
 public Result queryBlogById(Long id) {
  Blog blog = getById(id);
  if (blog == null) {
   return Result.fail("笔记不存在");
  }
  queryBlogUser(blog);
  isBlogLiked(blog);
  return Result.ok(blog);
 }

 @Override
 public Result likeBlog(Long id) {
  //判断笔记是否存在
  if (getById(id) == null) {
   return Result.fail("笔记不存在");
  }
  Long userId = UserHolder.getUser().getId();
  String key = LIKED_KEY + id;
  //查 ZSet 里的 score：null 表示没点过赞
  Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
  if (score == null) {
   //未点赞：数据库 liked+1，成功后写入 ZSet（score=时间戳，供排行榜按时间排序）
   boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
   if (isSuccess) {
    stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
   }
  } else {
   //已点赞：取消点赞，liked-1（gt 防止减成负数），成功后从 ZSet 移除
   boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
   if (isSuccess) {
    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
   }
  }
  return Result.ok();
 }

 @Override
 public Result queryBlogLikes(Long id) {
  //取点赞时间最早的 5 个用户（ZSet 按 score 升序）
  Set<String> top5 = stringRedisTemplate.opsForZSet().range(LIKED_KEY + id, 0, 4);
  if (top5 == null || top5.isEmpty()) {
   return Result.ok(Collections.emptyList());
  }
  List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
  //按 ZSet 的顺序返回（ORDER BY FIELD 保证顺序，不然会被数据库默认排序打乱）
  List<UserDTO> userDTOS = userService.query()
    .in("id", ids)
    .last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")")
    .list().stream()
    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
    .collect(Collectors.toList());
  return Result.ok(userDTOS);
 }

 @Override
 public Result saveBlog(Blog blog) {
  // 获取登录用户
  UserDTO user = UserHolder.getUser();
  blog.setUserId(user.getId());
  // 保存探店博文
 boolean isSuccess =  save(blog);
 if(!isSuccess)
 {
  return Result.fail("保存笔记失败");
 }
  //查询笔记作者的所有粉丝
  List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
  //推送笔记
  for(Follow follow : follows)
  {
     Long userId = follow.getUserId();
     String key =FEED_KEY + userId;
     stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

  }

  // 返回id
  return Result.ok(blog.getId());
 }

 @Override
 public Result queryBlogOfFollow(Long max, Integer offset) {
  //获取当前用户
  Long userId = UserHolder.getUser().getId();
  //查询收件箱
  String key = FEED_KEY + userId;
  Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
          .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
  if (typedTuples == null || typedTuples.isEmpty()) {
   return Result.ok(new ScrollResult());
  }
  //解析数据: blogId.minTime,offset
  List<Long> ids = new ArrayList<>(typedTuples.size());
  long minTime = 0;
  int os = 1;
  for (ZSetOperations.TypedTuple<String> tuple : typedTuples)
  {
   String idStr = tuple.getValue();
   ids.add(Long.valueOf(idStr));
   if(tuple.getScore().longValue()==minTime)
   {
       os++;
   }
   else
   {
       minTime = tuple.getScore().longValue();
       os = 1;
   }
  }
  //根据id查询blog
  List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id,"
          + StrUtil.join(",", ids) + ")").list();
  for(Blog  blog : blogs)
  {
   queryBlogUser(blog);
   isBlogLiked(blog);
  }
  //封装并返回
  ScrollResult r = new ScrollResult();
  r.setList(blogs);
  r.setOffset(os);
  r.setMinTime(minTime);

  return Result.ok(r);
 }

}
