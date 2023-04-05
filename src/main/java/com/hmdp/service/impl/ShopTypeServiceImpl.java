package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private IShopTypeService typeService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key="cache:shopType";
        //1.从redis获取缓存数据
        List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.判断是否存在

        if (!typeList.isEmpty()){
            //3.存在返回数据
            return Result.ok(typeList);
        }
        //4.不存在，从数据库查询
        List<ShopType> shopTypeList= typeService.query().orderByAsc("sort").list();
        //5.判断是否存在
        if (shopTypeList.isEmpty()){
            //6.不存在，返回错误
            return Result.fail("查询不到店铺类型");
        }
            JSONUtil.toJsonStr(shopTypeList);
        typeList = shopTypeList.stream().map((item) ->
                JSONUtil.toJsonStr(item)).collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(key,typeList);
        //7.存在,添加到redis缓存中
        stringRedisTemplate.opsForList().leftPushAll(key,typeList);
        //8.返回数据
        return Result.ok(typeList);
    }
}
