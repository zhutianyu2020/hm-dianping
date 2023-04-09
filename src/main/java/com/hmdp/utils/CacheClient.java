package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final StringRedisTemplate stringRedisTemplate;


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 存入数据到redis
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassTrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中的是否是空值
        if (json != null) {
            //返回一个错误信息
            return null;
        }
        // 4.不存在，根据id生成数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        // 7.返回
        return r;
    }

    /**
     * 利用逻辑过期解决缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param lockKeyPrefix
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit, String lockKeyPrefix) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isBlank(json)) {
            // 3.不存在，直接返回
            return null;
        }
        //4.命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        //6.2 判断是否获取锁成功
        boolean isLock = tryLock(lockKey);

        if (isLock) {
            //假设线程A查询完数据库，重建缓存并释放锁，此时线程B恰好获得锁，如果不进行二次检测，线程B会再次重建缓存
            //判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                //未过期，直接返回店铺信息
                return r;
            }
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }

            });
        }
        //6.4 返回过期的信息
        return r;
    }

    /**
     * 利用互斥锁解决缓存击穿
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param lockKeyPrefix
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit, String lockKeyPrefix) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StringUtils.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中的是否是空值
        if (json != null) {
            //返回一个错误信息
            return null;
        }
        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit, lockKeyPrefix);
            }
            //4.4 成功，根据id查询数据库
            r = dbFallback.apply(id);
            //模拟重建的延时
            Thread.sleep(200);
            //5.不存在，返回错误
            if (r == null) {
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，写入redis
            this.set(key, r, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {

            //7 释放互斥锁
            unlock(lockKey);
        }
        //8. 返回
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
