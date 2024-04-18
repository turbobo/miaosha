package com.geekq.miaosha.rabbitmq;

import com.geekq.api.entity.GoodsVoOrder;
import com.geekq.api.utils.AbstractResultOrder;
import com.geekq.api.utils.ResultGeekQOrder;
import com.geekq.miaosha.redis.RedisService;
import com.geekq.miaosha.service.GoodsService;
import com.geekq.miaosha.service.MiaoshaService;
import com.geekq.miaosha.service.OrderService;
import com.geekq.miasha.entity.MiaoshaOrder;
import com.geekq.miasha.entity.MiaoshaUser;
import com.geekq.miasha.enums.enums.ResultStatus;
import com.geekq.miasha.exception.GlobleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MQReceiver {

    private static Logger log = LoggerFactory.getLogger(MQReceiver.class);

    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    private com.geekq.api.service.GoodsService goodsServiceRpc;

//		@Autowired
//        MiaoShaMessageService messageService ;

    // TODO 任一步骤失败，直接将失败订单写入redis 不写入数据库，这样用户查询即可获取失败结果
    // TODO mq处理扣库存和下订单的整个流程，只要有不满足或者错误，直接写空order存入redis，前端查询直接返回失败
    @RabbitListener(queues = MQConfig.MIAOSHA_QUEUE)
    public void receive(String message) {
        log.info("receive message:" + message);
        MiaoshaUser user = null;
        Long goodsId = null;
        try {
            MiaoshaMessage mm = RedisService.stringToBean(message, MiaoshaMessage.class);
            user = mm.getUser();
            goodsId = mm.getGoodsId();

//			GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);
            ResultGeekQOrder<GoodsVoOrder> goodsVoOrderResultGeekQOrder = goodsServiceRpc.getGoodsVoByGoodsId(goodsId);
            if (!AbstractResultOrder.isSuccess(goodsVoOrderResultGeekQOrder)) {
                // 直接写入redis,判断抢购失败,直接返回，不执行后面的逻辑
//                miaoshaService.miaoshaOrderFail(user, goodsId);
                throw new GlobleException(ResultStatus.SESSION_ERROR);
            }

            GoodsVoOrder goods = goodsVoOrderResultGeekQOrder.getData();
            int stock = goods.getStockCount();
            if (stock <= 0) {
                // 商品不足，写入本地缓存，删除redis（重新从数据库查）
//                miaoshaService.miaoshaOrderFail(user, goodsId);
//                return;

                // 库存不够，“商品已经秒杀完毕”
                throw new GlobleException(ResultStatus.MIAO_SHA_OVER);
            }
            //判断是否已经秒杀到了
            MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(Long.valueOf(user.getNickname()), goodsId);
            if (order != null) {
                // 秒杀失败，订单已存在
//                miaoshaService.miaoshaOrderFail(user, goodsId);
                throw new GlobleException(ResultStatus.REPEATE_MIAOSHA);
            }
            // 减库存 下订单 写入秒杀订单
            // TODO 下述方法的所有操作都不捕获异常，用一个事务捕获 遇到任何异常就回滚，
            miaoshaService.miaosha(user, goods);
        } catch (Exception e) {
            log.error("下单异常");
            // TODO 遇到异常，直接 设置空order放入redis，页面获取直接为 抢购失败
            miaoshaService.miaoshaOrderFail(user, goodsId);
        }

    }


//	@RabbitListener(queues=MQConfig.MIAOSHATEST)
//	public void receiveMiaoShaMessage(Message message, Channel channel) throws IOException {
//		log.info("接受到的消息为:{}",message);
//		String messRegister = new String(message.getBody(), "UTF-8");
//		channel.basicAck(message.getMessageProperties().getDeliveryTag(), true);
//		MiaoShaMessageVo msm  = RedisService.stringToBean(messRegister, MiaoShaMessageVo.class);
//		messageService.insertMs(msm);
//		}
}
