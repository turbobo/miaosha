package com.geekq.miaosha.service;

import com.geekq.api.entity.GoodsVoOrder;
import com.geekq.miaosha.mapper.OrderMapper;
import com.geekq.miaosha.redis.GoodsKey;
import com.geekq.miaosha.redis.OrderKey;
import com.geekq.miaosha.redis.RedisService;
import com.geekq.miasha.entity.MiaoshaOrder;
import com.geekq.miasha.entity.MiaoshaUser;
import com.geekq.miasha.entity.OrderInfo;
import com.geekq.miasha.utils.DateTimeUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static com.geekq.miasha.enums.Constanst.orderStaus.ORDER_NOT_PAY;


@Service
public class OrderService {

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    private RedisService redisService;

    public MiaoshaOrder getMiaoshaOrderByUserIdGoodsId(long userId, long goodsId) {
        return redisService.get(OrderKey.getMiaoshaOrderByUidGid, "" + userId + "_" + goodsId, MiaoshaOrder.class);
    }

    public OrderInfo getOrderById(long orderId) {
        return orderMapper.getOrderById(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public OrderInfo createOrder(MiaoshaUser user, GoodsVoOrder goods) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setCreateDate(new Date());
        orderInfo.setDeliveryAddrId(0L);
        orderInfo.setGoodsCount(1);
        orderInfo.setGoodsId(goods.getId());
        orderInfo.setGoodsName(goods.getGoodsName());
        orderInfo.setGoodsPrice(goods.getMiaoshaPrice());
        orderInfo.setOrderChannel(1);
        orderInfo.setStatus(0);
        orderInfo.setUserId(Long.valueOf(user.getNickname()));
        orderMapper.insert(orderInfo);
        MiaoshaOrder miaoshaOrder = new MiaoshaOrder();
        miaoshaOrder.setGoodsId(goods.getId());
        miaoshaOrder.setOrderId(orderInfo.getId());
        miaoshaOrder.setUserId(Long.valueOf(user.getNickname()));
        orderMapper.insertMiaoshaOrder(miaoshaOrder);

        // redis标记 用户+商品 订单存在
        redisService.set(OrderKey.getMiaoshaOrderByUidGid, "" + user.getNickname() + "_" + goods.getId(), miaoshaOrder);


        // TODO 创建访客业务订单逻辑
        createVisitorOrder();

        // TODO reids操作：删除redis 库存的缓存，需要从数据库中更新余票的最新值
        redisService.del(GoodsKey.getMiaoshaGoodsStock.getPrefix() + Long.valueOf(goods.getId()));
        return orderInfo;
    }
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void createVisitorOrder () {

    }

    public void closeOrder(int hour) {
        Date closeDateTime = DateUtils.addHours(new Date(), -hour);
        List<OrderInfo> orderInfoList = orderMapper.selectOrderStatusByCreateTime(Integer.valueOf(ORDER_NOT_PAY.ordinal()), DateTimeUtils.dateToStr(closeDateTime));
        for (OrderInfo orderInfo : orderInfoList) {
            System.out.println("orderinfo  infomation " + orderInfo.getGoodsName());
        }
    }


}
