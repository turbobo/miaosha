package com.geekq.miaosha.controller;

import com.geekq.miaosha.interceptor.RequireLogin;
import com.geekq.miaosha.rabbitmq.MQSender;
import com.geekq.miaosha.rabbitmq.MiaoshaMessage;
import com.geekq.miaosha.redis.GoodsKey;
import com.geekq.miaosha.redis.RedisService;
import com.geekq.miaosha.redis.redismanager.RedisLimitRateWithLUA;
import com.geekq.miaosha.service.GoodsService;
import com.geekq.miaosha.service.MiaoShaUserService;
import com.geekq.miaosha.service.MiaoshaService;
import com.geekq.miaosha.service.OrderService;
import com.geekq.miasha.entity.MiaoshaOrder;
import com.geekq.miasha.entity.MiaoshaUser;
import com.geekq.miasha.enums.resultbean.ResultGeekQ;
import com.geekq.miasha.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;

import static com.geekq.miasha.enums.enums.ResultStatus.*;


@Controller
@RequestMapping("/miaosha")
public class MiaoshaController implements InitializingBean {

    private static Logger logger = LoggerFactory.getLogger(MiaoshaController.class);

    @Autowired
    MiaoShaUserService userService;

    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    MQSender mqSender;

    private HashMap<Long, Boolean> localOverMap = new HashMap<Long, Boolean>();

    /**
     * QPS:1306
     * 5000 * 10
     * get　post get 幂等　从服务端获取数据　不会产生影响　　post 对服务端产生变化
     *
     * 流程
     * 口库存，之后下单，下单成功后，再去删除redis的余票缓存，防止回滚
     */
    @RequireLogin(seconds = 5, maxCount = 5, needLogin = true)
    @RequestMapping(value = "/{path}/do_miaosha", method = RequestMethod.POST)
    @ResponseBody
    public ResultGeekQ<Integer> miaosha(Model model, MiaoshaUser user, @PathVariable("path") String path,
                                        @RequestParam("goodsId") long goodsId, Long buyCount) {
        ResultGeekQ<Integer> result = ResultGeekQ.build();

        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }
        // 验证path TODO 改进为原子操作 lua
        boolean check = miaoshaService.checkPath(user, goodsId, path);
        if (!check) {
            result.withError(REQUEST_ILLEGAL.getCode(), REQUEST_ILLEGAL.getMessage());
            return result;
        }
//		//使用RateLimiter 限流
//		RateLimiter rateLimiter = RateLimiter.create(10);
//		//判断能否在1秒内得到令牌，如果不能则立即返回false，不会阻塞程序
//		if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
//			System.out.println("短期无法获取令牌，真不幸，排队也瞎排");
//			return ResultGeekQ.error(CodeMsg.MIAOSHA_FAIL);
//
//		}

        /**
         * 分布式限流
         */
        try {
//            RedisLimitRateWithLUA.accquire();
            RedisLimitRateWithLUA.accquireUser(user);
            // TODO 简单点：直接对单个用户限流，一分钟内多少次抢购，或者总抢购次数
        } catch (IOException e) {
            result.withError(EXCEPTION.getCode(), REPEATE_MIAOSHA.getMessage());
            return result;
        } catch (URISyntaxException e) {
            result.withError(EXCEPTION.getCode(), REPEATE_MIAOSHA.getMessage());
            return result;
        }

        //是否已经秒杀到
        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(Long.valueOf(user.getNickname()), goodsId);
        if (order != null) {
            result.withError(EXCEPTION.getCode(), REPEATE_MIAOSHA.getMessage());
            return result;
        }
        // 内存标记，减少redis访问
        // TODO  多实例，本地内存可能不准确，但是后面redis会执行判断，将结果再写入本地内存！！！！！！！！！！！！！！
        boolean over = localOverMap.get(goodsId);
        if (over) {
            result.withError(EXCEPTION.getCode(), MIAO_SHA_OVER.getMessage());
            return result;
        }
        // TODO reids操作：只更新redis自己的值，预减库存，只是作为临时库存，后面 数据库扣库存成功，会删除缓存从数据库查询最新值
        boolean stock = false;
        try {
            stock = redisService.decrCount(GoodsKey.getMiaoshaGoodsStock.getPrefix() + goodsId, buyCount);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
//        Long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
        if (!stock) {  // 判断是否足够  库存是否满足当前购票数
            localOverMap.put(goodsId, true);
            result.withError(EXCEPTION.getCode(), MIAO_SHA_OVER.getMessage());
            return result; // TODO 预减库存直接失败，则返回抢购失败
        }
        // TODO 代码中提前创建好mq
        MiaoshaMessage mm = new MiaoshaMessage();
        mm.setGoodsId(goodsId);
        mm.setUser(user);
        mqSender.sendMiaoshaMessage(mm);
        return result;
        // TODO 此接口直接返回正在抢购中，订单异步生成了，页面返回抢购成功
        // 前端页面等待中，通过方法查询接口，是否下单成功来获取抢购结果

        // TODO 如果订单生成较慢，用户一直等待？
        // TODO 订单异步生成，中间逻辑失败了，用户如何知道秒杀失败
    }


    /**
     * orderId：成功
     * -1：秒杀失败
     * 0： 排队中
     */
    @RequireLogin(seconds = 5, maxCount = 5, needLogin = true)
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<Long> miaoshaResult(Model model, MiaoshaUser user,
                                           @RequestParam("goodsId") long goodsId) {
        ResultGeekQ<Long> result = ResultGeekQ.build();
        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }
        model.addAttribute("user", user);
        // TODO key存在，但是没有订单信息，只有失败，则直接返回失败
        Long miaoshaResult = miaoshaService.getMiaoshaResult(Long.valueOf(user.getNickname()), goodsId);
        result.setData(miaoshaResult);
        return result;
    }

    @RequireLogin(seconds = 5, maxCount = 5, needLogin = true)
    @RequestMapping(value = "/path", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<String> getMiaoshaPath(HttpServletRequest request, MiaoshaUser user,
                                              @RequestParam("goodsId") long goodsId,
                                              @RequestParam(value = "verifyCode", defaultValue = "0") int verifyCode
    ) {
        ResultGeekQ<String> result = ResultGeekQ.build();
        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }
        boolean check = miaoshaService.checkVerifyCode(user, goodsId, verifyCode);
        if (!check) {
            result.withError(REQUEST_ILLEGAL.getCode(), REQUEST_ILLEGAL.getMessage());
            return result;
        }
        String path = miaoshaService.createMiaoshaPath(user, goodsId);
        result.setData(path);
        return result;
    }

    @RequestMapping(value = "/verifyCodeRegister", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<String> getMiaoshaVerifyCod(HttpServletResponse response
    ) {
        ResultGeekQ<String> result = ResultGeekQ.build();
        try {
            BufferedImage image = miaoshaService.createVerifyCodeRegister();
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "JPEG", out);
            out.flush();
            out.close();
            return result;
        } catch (Exception e) {
            logger.error("生成验证码错误-----注册:{}", e);
            result.withError(MIAOSHA_FAIL.getCode(), MIAOSHA_FAIL.getMessage());
            return result;
        }
    }


    @RequestMapping(value = "/verifyCode", method = RequestMethod.GET)
    @ResponseBody
    public ResultGeekQ<String> getMiaoshaVerifyCod(HttpServletResponse response, MiaoshaUser user,
                                                   @RequestParam("goodsId") long goodsId) {
        ResultGeekQ<String> result = ResultGeekQ.build();
        if (user == null) {
            result.withError(SESSION_ERROR.getCode(), SESSION_ERROR.getMessage());
            return result;
        }
        try {
            BufferedImage image = miaoshaService.createVerifyCode(user, goodsId);
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "JPEG", out);
            out.flush();
            out.close();
            return result;
        } catch (Exception e) {
            logger.error("生成验证码错误-----goodsId:{}", goodsId, e);
            result.withError(MIAOSHA_FAIL.getCode(), MIAOSHA_FAIL.getMessage());
            return result;
        }
    }

    /**
     * 系统初始化
     *
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        List<GoodsVo> goodsList = goodsService.listGoodsVo();
        if (goodsList == null) {
            return;
        }
        for (GoodsVo goods : goodsList) {
            redisService.set(GoodsKey.getMiaoshaGoodsStock, "" + goods.getId(), goods.getStockCount());
            localOverMap.put(goods.getId(), false);
        }
    }
}
