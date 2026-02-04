package com.example.tomatomall.service.serviceImpl;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson.JSONObject;
import com.example.tomatomall.config.AliPayConfig;
import com.example.tomatomall.dao.*;
import com.example.tomatomall.dto.PaymentNotifyDTO;
import com.example.tomatomall.po.*;
import com.example.tomatomall.service.CartService;
import com.example.tomatomall.service.OrderService;
import com.example.tomatomall.service.ProductService;
import com.example.tomatomall.service.StockpileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Resource
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Resource
    private CartItemRepository cartRepository;

    @Resource
    private ProductRepository productRepository;

    @Autowired
    private CartOrderRelationRepository cartOrderRelationRepository;

    @Autowired
    private StockpileRepository stockpileRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockpileService stockpileService;

    @Autowired
    private AccountRepository accountRepository;

    @Resource
    private AliPayConfig aliPayConfig;

    @Autowired
    private CartItemRepository cartItemRepository;

    // [恢复] Redisson 客户端 (用于分布式锁，防超卖)
    @Autowired
    private RedissonClient redissonClient;

    // [新增] RocketMQ 模版 (用于延时消息)
    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private CartService cartService;

    @Override
    @Transactional
    public Order createOrder(Integer userId, List<Integer> cartItemIds, Object shippingAddress, String paymentMethod) {
        // 1. 批量获取并验证数据
        List<CartItem> validCartItems = getAndValidateCartItems(userId, cartItemIds);
        Map<Integer, Product> productMap = getProductMap(validCartItems);

        // 2. 处理库存并计算总金额 (这里面有核心的锁逻辑)
        BigDecimal totalAmount = processStockAndCalculateTotal(validCartItems, productMap);

        // 3. 创建订单并保存关联
        Order order = createOrderEntity(userId, paymentMethod, totalAmount);

        // 新增: 保存订单项记录
        saveOrderItems(validCartItems, order, productMap);
        saveCartOrderRelations(validCartItems, order);

        // [恢复] 发送 RocketMQ 延时消息
        sendDelayOrderMessage(order);

        log.info("订单创建成功: {}", order.getOrderId());

        // 创建订单后删除购物车中的商品
        if (cartItemIds != null && !cartItemIds.isEmpty()) {
            cartItemIds.forEach(cartItemId -> {
                try {
                    cartService.removeCartItem(cartItemId);
                    log.info("已从购物车移除商品: {}", cartItemId);
                } catch (Exception e) {
                    log.error("从购物车移除商品失败: {}", cartItemId, e);
                }
            });
        }

        return order;
    }

    // RocketMQ 发送逻辑
    private void sendDelayOrderMessage(Order order) {
        Map<String, Object> msgMap = new HashMap<>();
        msgMap.put("orderId", order.getOrderId()); // 这里已经是 Long 类型
        msgMap.put("createTime", order.getCreateTime());

        String jsonString = JSONObject.toJSONString(msgMap);

        // 发送延时消息：Level 16 对应 30分钟
        rocketMQTemplate.syncSend("order-delay-topic",
                MessageBuilder.withPayload(jsonString).build(),
                3000,
                16);

        log.info("RocketMQ 延时消息发送成功 (30分钟后过期)，订单ID: {}", order.getOrderId());
    }

    private List<CartItem> getAndValidateCartItems(Integer userId, List<Integer> cartItemIds) {
        return cartItemRepository.findAllByCartItemIdIn(cartItemIds);
    }

    // [核心恢复] 恢复了 Redisson 分布式锁
    private BigDecimal processStockAndCalculateTotal(List<CartItem> cartItems, Map<Integer, Product> productMap) {
        BigDecimal total = BigDecimal.ZERO;

        for (CartItem cartItem : cartItems) {
            Product product = productMap.get(cartItem.getProductId());
            Integer productId = cartItem.getProductId();

            // 1. 获取分布式锁
            RLock lock = redissonClient.getLock("lock:stock:" + productId);
            try {
                // 2. 尝试加锁 (等待3秒，持有锁30秒自动释放)
                lock.lock();
                boolean isLocked = lock.tryLock(3, 30, TimeUnit.SECONDS);
                if (!isLocked) {
                    throw new RuntimeException("系统繁忙，抢购人数过多，请稍后再试");
                }

                // 3. [锁内逻辑]
                Stockpile stockpile = productService.getStock(productId);

                if (stockpile.getAmount() < cartItem.getQuantity()) {
                    throw new RuntimeException("商品库存不足: " + product.getTitle());
                }

                stockpile.setAmount(stockpile.getAmount() - cartItem.getQuantity());
                stockpile.setLockedAmount(stockpile.getLockedAmount() + cartItem.getQuantity());

                stockpileService.updateStockpile(stockpile);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("锁定库存时发生中断异常");
            } finally {
                // 4. 释放锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
            log.info("当前商品: {} 数量: {} 单价: {} 总金额: {}",
                    product.getTitle(), cartItem.getQuantity(), product.getPrice(), total);
        }

        return total;
    }

    private Map<Integer, Product> getProductMap(List<CartItem> cartItems) {
        Set<Integer> productIds = cartItems.stream()
                .map(CartItem::getProductId)
                .collect(Collectors.toSet());

        Map<Integer, Product> productMap = new HashMap<>();
        List<Product> products = productRepository.findAllById(productIds);

        for (Product product : products) {
            productMap.put(product.getId(), product);
        }

        cartItems.forEach(cartItem -> {
            if (!productMap.containsKey(cartItem.getProductId())) {
                throw new RuntimeException("商品不存在: " + cartItem.getProductId());
            }
        });

        return productMap;
    }

    private Order createOrderEntity(Integer userId, String paymentMethod, BigDecimal totalAmount) {
        Order order = new Order();
        order.setUserId(userId);
        String username = accountRepository.findByUserId(userId).getUsername();
        order.setUsername(username);

        // [核心修改] 使用 Hutool 雪花算法生成 Long 类型 ID
        // getSnowflake(workerId, datacenterId)
        long snowflakeId = IdUtil.getSnowflake(1, 1).nextId();
        order.setOrderId(snowflakeId);

        order.setTotalAmount(totalAmount);
        order.setPaymentMethod(paymentMethod);
        order.setStatus("PENDING");
        // 设置数据库层面的过期时间
        order.setLockExpireTime(new Timestamp(System.currentTimeMillis() + 30 * 60 * 1000));
        log.info("创建订单: ID={}, 用户ID={}, 金额={}", snowflakeId, userId, totalAmount);
        return orderRepository.save(order);
    }

    private void saveCartOrderRelations(List<CartItem> cartItems, Order order) {
        List<CartOrderRelation> relations = new ArrayList<>(cartItems.size());
        for (CartItem cartItem : cartItems) {
            // 注意：这里 order.getOrderId() 返回的是 Long
            // 你需要确认 CartOrderRelation 的 orderId 字段也改成了 Long，否则这里会报错
            relations.add(CartOrderRelation.of(cartItem.getCartItemId(), order.getOrderId()));
        }
        cartOrderRelationRepository.saveAll(relations);
    }

    @Override
    public Order getOrderById(Long orderId) { // 参数改为 Long
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单未找到"));
    }

    // ... 其他 getter 方法 ...
    @Override
    public Order getOrderByTradeNo(String tradeNo) {
        return orderRepository.findByTradeNo(tradeNo).orElseThrow(() -> new RuntimeException("订单未找到"));
    }
    @Override
    public List<Order> getOrdersByUserId(Integer userId) {
        return orderRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public Order updateOrderStatus(Long orderId, String status) { // 参数改为 Long
        Order order = getOrderById(orderId);
        order.setStatus(status);
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public boolean processPaymentCallback(PaymentNotifyDTO paymentNotifyDTO) throws Exception {
        String orderIdStr = paymentNotifyDTO.getOutTradeNo(); // 此时是纯数字字符串
        String alipayTradeNo = paymentNotifyDTO.getTradeNo();
        BigDecimal actualAmount = paymentNotifyDTO.getTotalAmount();

        // [核心修改] 将字符串解析为 Long
        Order order = getOrderById(Long.parseLong(orderIdStr));

        // 1. 校验金额
        if (order.getTotalAmount().compareTo(actualAmount) != 0) {
            log.warn("订单金额不一致！订单ID：{}", order.getOrderId());
            throw new RuntimeException("支付金额校验失败");
        }

        // 2. 幂等性校验
        if ("SUCCESS".equals(order.getStatus())) {
            return true;
        }

        // 3. 释放锁定库存
        List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(order.getOrderId());
        relations.forEach(relation -> {
            CartItem cartItem = cartRepository.findById(relation.getCartItemId())
                    .orElseThrow(() -> new RuntimeException("购物车项不存在"));

            Stockpile stockpile = stockpileRepository.findByProductId(cartItem.getProductId()).get();
            synchronized (stockpile) {
                stockpile.setLockedAmount(stockpile.getLockedAmount() - cartItem.getQuantity());
                stockpileRepository.save(stockpile);
            }
        });

        // 4. 清理购物车
        List<Integer> cartItemIds = relations.stream()
                .map(CartOrderRelation::getCartItemId)
                .collect(Collectors.toList());
        cartRepository.deleteAllById(cartItemIds);
        cartOrderRelationRepository.deleteAll(relations);

        // 5. 更新状态
        order.setStatus("SUCCESS");
        order.setTradeNo(alipayTradeNo);
        order.setPaymentTime(new Timestamp(System.currentTimeMillis()));
        orderRepository.save(order);
        return true;
    }

    @Transactional
    @Override
    public void handleExpiredOrder(Long orderId) { // 参数改为 Long
        Order order = getOrderById(orderId);
        if ("PENDING".equals(order.getStatus()) &&
                order.getLockExpireTime().before(new Timestamp(System.currentTimeMillis()))) {

            log.info("订单超时，开始回滚库存。ID: {}", orderId);
            List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(orderId);

            relations.forEach(relation -> {
                CartItem cartItem = cartRepository.findById(relation.getCartItemId()).orElse(null);
                if (cartItem != null) {
                    Stockpile stockpile = productService.getStock(cartItem.getProductId());
                    // 恢复库存
                    stockpile.setAmount(stockpile.getAmount() + cartItem.getQuantity());
                    stockpile.setLockedAmount(stockpile.getLockedAmount() - cartItem.getQuantity());
                    stockpileService.updateStockpile(stockpile);
                }
            });

            order.setStatus("TIMEOUT");
            orderRepository.save(order);
        }
    }

    // 其他辅助方法
    @Override
    public List<Order> findExpiredOrders() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return orderRepository.findByStatusAndLockExpireTimeBefore("PENDING", now);
    }

    @Override
    public Order updateOrder(Order order) {
        return orderRepository.save(order);
    }

    @Override
    public boolean checkPaymentStatusWithAlipay(Long orderId) throws Exception { // 参数改为 Long
        // 这里的代码如果你还没有配置好支付宝密钥，可以先 return false
        // 或者保留原有的 try-catch 逻辑
        try {
            Order order = getOrderById(orderId);
            if (order == null) return false;
            // ... 真实查询逻辑 ...
            return false;
        } catch (Exception e) {
            log.error("查询支付状态异常", e);
            throw e;
        }
    }

    @Override
    @Transactional
    public Order cancelOrder(Long orderId, Integer userId) throws Exception { // 参数改为 Long
        Order order = getOrderById(orderId);

        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作此订单");
        }

        if (!"PENDING".equals(order.getStatus())) {
            throw new RuntimeException("只能取消待支付的订单");
        }

        List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(orderId);
        // ... 恢复库存逻辑同超时 ...
        relations.forEach(relation -> {
            CartItem cartItem = cartRepository.findById(relation.getCartItemId()).orElse(null);
            if (cartItem != null) {
                Stockpile stockpile = productService.getStock(cartItem.getProductId());
                stockpile.setAmount(stockpile.getAmount() + cartItem.getQuantity());
                stockpile.setLockedAmount(stockpile.getLockedAmount() - cartItem.getQuantity());
                stockpileService.updateStockpile(stockpile);
            }
        });

        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public Order confirmReceipt(Long orderId, Integer userId) throws Exception { // 参数改为 Long
        Order order = getOrderById(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作此订单");
        }
        if (!"SHIPPED".equals(order.getStatus()) && !"DELIVERED".equals(order.getStatus())) {
            throw new RuntimeException("只能确认已发货的订单");
        }
        order.setStatus("COMPLETED");
        return orderRepository.save(order);
    }

    private void saveOrderItems(List<CartItem> cartItems, Order order, Map<Integer, Product> productMap) {
        List<OrderItem> orderItems = new ArrayList<>(cartItems.size());
        for (CartItem cartItem : cartItems) {
            Product product = productMap.get(cartItem.getProductId());
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setPrice(product.getPrice());
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);
        log.info("保存订单项: {} 条记录", orderItems.size());
    }
}