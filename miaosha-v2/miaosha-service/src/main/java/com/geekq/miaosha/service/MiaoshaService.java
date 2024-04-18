package com.geekq.miaosha.service;

import com.alibaba.dubbo.config.annotation.Reference;
import com.geekq.api.entity.GoodsVoOrder;
import com.geekq.miaosha.redis.MiaoshaKey;
import com.geekq.miaosha.redis.OrderKey;
import com.geekq.miaosha.redis.RedisService;
import com.geekq.miasha.entity.MiaoshaOrder;
import com.geekq.miasha.entity.MiaoshaUser;
import com.geekq.miasha.entity.OrderInfo;
import com.geekq.miasha.utils.MD5Utils;
import com.geekq.miasha.utils.UUIDUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

@Service
public class MiaoshaService {

    private static char[] ops = new char[]{'+', '-', '*'};
    @Autowired
    GoodsService goodsService;
    @Autowired
    OrderService orderService;
    @Autowired
    RedisService redisService;

    @Reference(version = "${demo.service.version}", retries = 3, timeout = 6000)
    private com.geekq.api.service.GoodsService goodsServiceRpc;

    private static int calc(String exp) {
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            Integer catch1 = (Integer) engine.eval(exp);
            return catch1.intValue();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * spring默认捕获运行时异常及其子类RuntimeException
     * 如果要捕获其他异常，需要手动声明rollback
     *
     * 事务捕获有两种方式：
     * 1、整个代码块不做任何try-catch，方法上也不声明 throws XXXException，异常都交给事务捕获
     * 2、方法内代码块正常使用try-catch，但是在catch中需要手动throw new XXXException，让事务捕获，
     * 好处是，发生异常的时候可以在catch中记录日志
     * @param user
     * @param goods
     * @return
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public OrderInfo miaosha(MiaoshaUser user, GoodsVoOrder goods) {
        //减库存 下订单 写入秒杀订单
//		boolean success = goodsService.reduceStock(goods);
        // TODO 扣库存加锁，使用乐观锁，版本号控制 + 订单记录表根据user和商品限制唯一约束
        // TODO 直接将商品库存和数据库的实际库存对比，作为版本控制
        boolean success = goodsServiceRpc.reduceStock(goods);
        if (success) {
            return orderService.createOrder(user, goods);
        } else {
            // 如果库存不存在则内存标记为true
            // TODO 刷新redis缓存数量，或者直接删除
            setGoodsOver(goods.getId());  // 无需要保存标记
            miaoshaOrderFail(user, goods.getId());
            return null;
        }
        // 更新redis
    }

    /**
     * 直接写入redis,判断抢购失败,直接返回，不执行后面的逻辑
     * @param user
     * @param goodsId
     */
    public void miaoshaOrderFail(MiaoshaUser user, Long goodsId) {
        // 创建空的订单，表示下单失败
        MiaoshaOrder miaoshaOrder = new MiaoshaOrder();
        redisService.set(OrderKey.getMiaoshaOrderByUidGid, "" + user.getNickname() + "_" + goodsId, miaoshaOrder);
    }

    public long getMiaoshaResult(Long userId, long goodsId) {
        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(userId, goodsId);
        if (order != null) {//秒杀成功
            // TODO 下单失败：订单信息不完整
            if (null == order.getOrderId() || null == order.getUserId() || null == order.getGoodsId()) {
                return -1;
            }
            return order.getOrderId();
        } else {
            // 直接从redis获取余票数量
            boolean isOver = getGoodsOver(goodsId);
            if (isOver) {
                // 秒杀结束
                return -1;
            } else {
                return 0;
            }
        }
    }

    private void setGoodsOver(Long goodsId) {
        redisService.set(MiaoshaKey.isGoodsOver, "" + goodsId, true);
    }

    private boolean getGoodsOver(long goodsId) {
        return redisService.exists(MiaoshaKey.isGoodsOver, "" + goodsId);
    }

    public boolean checkPath(MiaoshaUser user, long goodsId, String path) {
        if (user == null || path == null) {
            return false;
        }
        String pathOld = redisService.get(MiaoshaKey.getMiaoshaPath, "" + user.getNickname() + "_" + goodsId, String.class);
        return path.equals(pathOld);
    }

    public String createMiaoshaPath(MiaoshaUser user, long goodsId) {
        if (user == null || goodsId <= 0) {
            return null;
        }
        String str = MD5Utils.md5(UUIDUtil.uuid() + "123456");
        redisService.set(MiaoshaKey.getMiaoshaPath, "" + user.getNickname() + "_" + goodsId, str);
        return str;
    }

    public BufferedImage createVerifyCode(MiaoshaUser user, long goodsId) {
        if (user == null || goodsId <= 0) {
            return null;
        }
        int width = 80;
        int height = 32;
        //create the image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        // set the background color
        g.setColor(new Color(0xDCDCDC));
        g.fillRect(0, 0, width, height);
        // draw the border
        g.setColor(Color.black);
        g.drawRect(0, 0, width - 1, height - 1);
        // create a random instance to generate the codes
        Random rdm = new Random();
        // make some confusion
        for (int i = 0; i < 50; i++) {
            int x = rdm.nextInt(width);
            int y = rdm.nextInt(height);
            g.drawOval(x, y, 0, 0);
        }
        // generate a random code
        String verifyCode = generateVerifyCode(rdm);
        g.setColor(new Color(0, 100, 0));
        g.setFont(new Font("Candara", Font.BOLD, 24));
        g.drawString(verifyCode, 8, 24);
        g.dispose();
        //把验证码存到redis中
        int rnd = calc(verifyCode);
        redisService.set(MiaoshaKey.getMiaoshaVerifyCode, user.getNickname() + "," + goodsId, rnd);
        //输出图片
        return image;
    }

    /**
     * 注册时用的验证码
     *
     * @param verifyCode
     * @return
     */
    public boolean checkVerifyCodeRegister(int verifyCode) {
        Integer codeOld = redisService.get(MiaoshaKey.getMiaoshaVerifyCodeRegister, "regitser", Integer.class);
        if (codeOld == null || codeOld - verifyCode != 0) {
            return false;
        }
        redisService.delete(MiaoshaKey.getMiaoshaVerifyCode, "regitser");
        return true;
    }

    public BufferedImage createVerifyCodeRegister() {
        int width = 80;
        int height = 32;
        //create the image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        // set the background color
        g.setColor(new Color(0xDCDCDC));
        g.fillRect(0, 0, width, height);
        // draw the border
        g.setColor(Color.black);
        g.drawRect(0, 0, width - 1, height - 1);
        // create a random instance to generate the codes
        Random rdm = new Random();
        // make some confusion
        for (int i = 0; i < 50; i++) {
            int x = rdm.nextInt(width);
            int y = rdm.nextInt(height);
            g.drawOval(x, y, 0, 0);
        }
        // generate a random code
        String verifyCode = generateVerifyCode(rdm);
        g.setColor(new Color(0, 100, 0));
        g.setFont(new Font("Candara", Font.BOLD, 24));
        g.drawString(verifyCode, 8, 24);
        g.dispose();
        //把验证码存到redis中
        int rnd = calc(verifyCode);
        redisService.set(MiaoshaKey.getMiaoshaVerifyCodeRegister, "regitser", rnd);
        //输出图片
        return image;
    }

    public boolean checkVerifyCode(MiaoshaUser user, long goodsId, int verifyCode) {
        if (user == null || goodsId <= 0) {
            return false;
        }
        Integer codeOld = redisService.get(MiaoshaKey.getMiaoshaVerifyCode, user.getNickname() + "," + goodsId, Integer.class);
        if (codeOld == null || codeOld - verifyCode != 0) {
            return false;
        }
        redisService.delete(MiaoshaKey.getMiaoshaVerifyCode, user.getNickname() + "," + goodsId);
        return true;
    }

    /**
     * + - *
     */
    private String generateVerifyCode(Random rdm) {
        int num1 = rdm.nextInt(10);
        int num2 = rdm.nextInt(10);
        int num3 = rdm.nextInt(10);
        char op1 = ops[rdm.nextInt(3)];
        char op2 = ops[rdm.nextInt(3)];
        String exp = "" + num1 + op1 + num2 + op2 + num3;
        return exp;
    }

}
