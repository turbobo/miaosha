package com.geekq.miaosha.redis.redismanager;

import com.geekq.miasha.entity.MiaoshaUser;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class RedisLimitRateWithLUA {

    public static boolean accquire() throws IOException, URISyntaxException {
        Jedis jedis = new Jedis("39.107.245.253");

        String lua =
                "local key = KEYS[1] " +
                        " local limit = tonumber(ARGV[1]) " +
                        " local current = tonumber(redis.call('get', key) or '0')" +
                        " if current + 1 > limit " +
                        " then  return 0 " +
                        " else " +
                        " redis.call('INCRBY', key,'1')" +
                        " redis.call('expire', key,'2') " +
                        " end return 1 ";

        String key = "ip:" + System.currentTimeMillis() / 1000; // 当前秒
        String limit = "3"; // 最大限制
        List<String> keys = new ArrayList<String>();
        keys.add(key);
        List<String> args = new ArrayList<String>();
        args.add(limit);
        jedis.auth("youxin11");
        String luaScript = jedis.scriptLoad(lua);
        Long result = (Long) jedis.evalsha(luaScript, keys, args);
        return result == 1;
    }

    // 用户限制
    public static boolean accquireUser(MiaoshaUser user) throws IOException, URISyntaxException {
        Jedis jedis = new Jedis("39.107.245.253");

        String lua =
                "local key = KEYS[1] " +
                        " local limit = tonumber(ARGV[1]) " +
                        " local current = tonumber(redis.call('get', key) or '0')" +
                        " if current + 1 > limit " +
                        " then  return 0 " +
                        " else " +
                        " redis.call('INCRBY', key,'1')" +
                        " redis.call('expire', key,'60') " +
                        " end return 1 ";

        // TODO 用户限制  一分钟20次
        String key = String.valueOf(user.getId()); // 当前用户
        String limit = "20"; // 最大限制
        List<String> keys = new ArrayList<String>();
        keys.add(key);
        List<String> args = new ArrayList<String>();
        args.add(limit);
        jedis.auth("youxin11");
        String luaScript = jedis.scriptLoad(lua);
        Long result = (Long) jedis.evalsha(luaScript, keys, args);
        return result == 1;
    }

    // redis扣除指定库存数量
    public static boolean decrCount(String goodsId, Long buyConut) throws IOException, URISyntaxException {
        Jedis jedis = new Jedis("39.107.245.253");

        String lua =
                "local key = KEYS[1] " +
                        " local buyCount = tonumber(ARGV[1]) " +  // 需要购买的数量
                        " local stock = tonumber(redis.call('get', key) or '0')" +   // redis中的 商品库存数量
                        " if stock > buyCount " +  // 库存满足购买量，则继续
                        " then  return 0 " +
                        " else " +
                        " redis.call('DECRBY', key, ARGV[1])" +  // 扣除库存
                        " end return 1 ";

        List<String> keys = new ArrayList<String>();
        keys.add(goodsId);
        List<String> args = new ArrayList<String>();
        args.add(String.valueOf(buyConut));
        jedis.auth("youxin11");
        String luaScript = jedis.scriptLoad(lua);
        Long result = (Long) jedis.evalsha(luaScript, keys, args);
        return result == 1;
    }
}
