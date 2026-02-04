package com.example.tomatomall.service;

import com.example.tomatomall.dto.PaymentNotifyDTO;
import com.example.tomatomall.po.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface OrderService {

    /**
     * 创建订单
     * 注意：userId 还是 Integer (对应 Users 表)，cartItemIds 也是 Integer
     */
    Order createOrder(Integer userId, List<Integer> cartItemIds, Object shippingAddress, String paymentMethod);

    /**
     * 根据ID获取订单
     * [修改点] 参数改为 Long
     */
    Order getOrderById(Long orderId);

    /**
     * 根据支付宝交易号获取订单
     */
    Order getOrderByTradeNo(String tradeNo);

    /**
     * 根据用户ID获取订单列表
     */
    List<Order> getOrdersByUserId(Integer userId);

    /**
     * 更新订单状态
     * [修改点] 参数改为 Long
     */
    Order updateOrderStatus(Long orderId, String status);

    /**
     * 处理支付回调（校验金额、扣库存、改状态）
     */
    boolean processPaymentCallback(PaymentNotifyDTO paymentNotifyDTO) throws Exception;

    /**
     * 处理超时订单
     * [修改点] 参数改为 Long
     */
    @Transactional
    void handleExpiredOrder(Long orderId);

    /**
     * 查找所有已超时的订单
     */
    List<Order> findExpiredOrders();

    /**
     * 取消订单
     * [修改点] 参数改为 Long
     */
    Order cancelOrder(Long orderId, Integer userId) throws Exception;

    /**
     * 确认收货
     * [修改点] 参数改为 Long
     */
    Order confirmReceipt(Long orderId, Integer userId) throws Exception;

    /**
     * 通过支付宝API主动检查订单支付状态
     * [修改点] 参数改为 Long
     */
    boolean checkPaymentStatusWithAlipay(Long orderId) throws Exception;

    /**
     * 更新订单信息
     */
    Order updateOrder(Order order);
}