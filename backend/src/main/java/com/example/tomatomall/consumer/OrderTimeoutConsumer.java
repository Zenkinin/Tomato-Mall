package com.example.tomatomall.consumer;

import com.alibaba.fastjson.JSONObject;
import com.example.tomatomall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单超时消费者
 * 监听 RocketMQ 的 order-delay-topic
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "order-delay-topic",        // 必须和发送端一致
        consumerGroup = "order-timeout-group" // 消费者组名，随意起
)
public class OrderTimeoutConsumer implements RocketMQListener<String> {

    @Autowired
    private OrderService orderService;

    @Override
    public void onMessage(String message) {
        try {
            // 1. 解析消息
            Map<String, Object> map = JSONObject.parseObject(message, Map.class);
            Long orderId = (Long) map.get("orderId");

            log.info("【RocketMQ】收到订单超时检查消息，订单ID：{}", orderId);

            // 2. 调用业务逻辑检查并关闭订单
            orderService.handleExpiredOrder(orderId);

        } catch (Exception e) {
            log.error("处理订单超时消息失败: {}", e.getMessage(), e);
            // RocketMQ 默认重试机制：如果抛出异常，消息会稍后重试
        }
    }
}