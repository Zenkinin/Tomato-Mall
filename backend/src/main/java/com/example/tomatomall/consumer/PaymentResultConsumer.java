package com.example.tomatomall.consumer;

import com.example.tomatomall.dto.PaymentNotifyDTO;
import com.example.tomatomall.po.Order;
import com.example.tomatomall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = "payment-success-topic",      // 监听的主题 (需要和Controller发送的一致)
        consumerGroup = "payment-consumer-group" // 消费者组名
)
// 注意：这里泛型直接写 PaymentNotifyDTO，RocketMQ 会自动反序列化 JSON
public class PaymentResultConsumer implements RocketMQListener<PaymentNotifyDTO> {

    @Autowired
    private OrderService orderService;

    @Override
    public void onMessage(PaymentNotifyDTO notifyDTO) {
        log.info("【RocketMQ】收到支付结果消息: {}", notifyDTO);

        try {
            // 1. 查询订单
            // 注意：这里可能会抛出异常(订单不存在)，如果抛出，RocketMQ会重试
            Order order = orderService.getOrderById(Integer.parseInt(notifyDTO.getOutTradeNo()));
            log.info("查询到订单信息: ID={} 金额={}", order.getOrderId(), order.getTotalAmount());

            // 2. 幂等性检查
            if ("SUCCESS".equals(order.getStatus())) {
                log.info("订单已处理过，直接跳过");
                return; // 方法正常结束，视为 ACK
            }

            // 3. 处理业务 (扣库存、改状态、清购物车)
            boolean success = orderService.processPaymentCallback(notifyDTO);

            if (success) {
                log.info("支付回调业务处理成功");
                // 方法结束 -> 自动 ACK
            } else {
                // 如果业务逻辑返回 false，抛出异常触发 RocketMQ 重试
                throw new RuntimeException("业务处理失败，触发重试");
            }

        } catch (Exception e) {
            log.error("支付回调处理异常: {}", e.getMessage());
            // 抛出异常 -> RocketMQ 会根据重试策略（1s 5s 10s...）再次投递这条消息
            throw new RuntimeException(e);
        }
    }
}