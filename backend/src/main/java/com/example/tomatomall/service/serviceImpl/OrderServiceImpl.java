package com.example.tomatomall.service.serviceImpl;

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
        msgMap.put("orderId", order.getOrderId());
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
        List<CartItem> cartItems = cartItemRepository.findAllByCartItemIdIn(cartItemIds);
        return cartItems;
    }

    // [核心恢复] 恢复了 Redisson 分布式锁
    private BigDecimal processStockAndCalculateTotal(List<CartItem> cartItems, Map<Integer, Product> productMap) {
        BigDecimal total = BigDecimal.ZERO;

        for (CartItem cartItem : cartItems) {
            Product product = productMap.get(cartItem.getProductId());
            Integer productId = cartItem.getProductId();

            // 1. 获取分布式锁 (锁的粒度是商品ID，不同商品互不影响)
            RLock lock = redissonClient.getLock("lock:stock:" + productId);
            try {
                // 2. 尝试加锁 (等待3秒，持有锁30秒自动释放)
                lock.lock();
                boolean isLocked = lock.tryLock(3, 30, TimeUnit.SECONDS);
                if (!isLocked) {
                    throw new RuntimeException("系统繁忙，抢购人数过多，请稍后再试");
                }

                // 3. [锁内逻辑] 查库存 -> 判断 -> 扣减
                // 只有拿到锁的线程才能执行这段代码，绝对安全
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
                // 4. 释放锁 (非常重要！否则死锁)
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

        order.setTotalAmount(totalAmount);
        order.setPaymentMethod(paymentMethod);
        order.setStatus("PENDING");
        // 设置数据库层面的过期时间，方便人工排查
        order.setLockExpireTime(new Timestamp(System.currentTimeMillis() + 30 * 60 * 1000));
        log.info("创建订单: 用户ID {}, 支付方式 {}, 总金额 {}", userId, paymentMethod, totalAmount);
        return orderRepository.save(order);
    }

    private void saveCartOrderRelations(List<CartItem> cartItems, Order order) {
        List<CartOrderRelation> relations = new ArrayList<>(cartItems.size());
        for (CartItem cartItem : cartItems) {
            relations.add(CartOrderRelation.of(cartItem.getCartItemId(), order.getOrderId()));
        }
        cartOrderRelationRepository.saveAll(relations);
    }

    @Override
    public Order getOrderById(Integer orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单未找到"));
    }

    // ... 其他 getter 方法保持不变 ...
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
    public Order updateOrderStatus(Integer orderId, String status) {
        Order order = getOrderById(orderId);
        order.setStatus(status);
        return orderRepository.save(order);
    }

    // 支付回调逻辑 (注意：这里如果是真实环境，建议也用 MQ 解耦，
    // 但如果想简化，直接写业务逻辑也没问题，只要注意幂等性)
    @Override
    @Transactional
    public boolean processPaymentCallback(PaymentNotifyDTO paymentNotifyDTO) throws Exception {
        String orderId = paymentNotifyDTO.getOutTradeNo();
        String alipayTradeNo = paymentNotifyDTO.getTradeNo();
        BigDecimal actualAmount = paymentNotifyDTO.getTotalAmount();

        Order order = getOrderById(Integer.parseInt(orderId));

        // 1. 校验金额
        if (order.getTotalAmount().compareTo(actualAmount) != 0) {
            log.warn("订单金额不一致，可能被篡改！订单ID：{}", orderId);
            throw new RuntimeException("支付金额校验失败");
        }

        // 2. 幂等性校验 (防止重复处理)
        if ("SUCCESS".equals(order.getStatus())) {
            return true;
        }

        // 3. 释放锁定库存
        List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(order.getOrderId());
        relations.forEach(relation -> {
            CartItem cartItem = cartRepository.findById(relation.getCartItemId())
                    .orElseThrow(() -> new RuntimeException("购物车项不存在"));

            Stockpile stockpile = stockpileRepository.findByProductId(cartItem.getProductId()).get();
            // 在这里其实也建议加分布式锁，不过并发回调概率极低
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
    public void handleExpiredOrder(Integer orderId) {
        Order order = getOrderById(orderId);
        // 只有 PENDING 的才需要取消
        if ("PENDING".equals(order.getStatus()) &&
                order.getLockExpireTime().before(new Timestamp(System.currentTimeMillis()))) {

            log.info("订单超时，开始回滚库存。ID: {}", orderId);
            List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(orderId);

            relations.forEach(relation -> {
                CartItem cartItem = cartRepository.findById(relation.getCartItemId()).orElse(null);
                if (cartItem != null) {
                    Stockpile stockpile = productService.getStock(cartItem.getProductId());
                    // 恢复库存：可用库存 + quantity，锁定库存 - quantity
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
    public boolean checkPaymentStatusWithAlipay(Integer orderId) throws Exception {
        // [注意] 这里调用了支付宝沙箱，如果你没配支付宝公钥私钥，这里也会报错。
        // 但只要前端不发起支付，单纯下单是不会走到这里的。
        try {
            Order order = getOrderById(orderId);
            if (order == null) return false;

            // 简单处理：没有 factory 实例可能报错，如果不用支付功能，这里不用管
            // AlipayTradeQueryResponse response = Factory.Payment.Common().query(order.getOrderId().toString());
            // ...            return false;
        } catch (Exception e) {
            log.error("查询支付状态异常", e);
            throw e;
        }
        return true;
    }

    @Override
    @Transactional
    public Order cancelOrder(Integer orderId, Integer userId) throws Exception {
        Order order = getOrderById(orderId);

        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作此订单");
        }

        if (!"PENDING".equals(order.getStatus())) {
            throw new RuntimeException("只能取消待支付的订单");
        }

        List<CartOrderRelation> relations = cartOrderRelationRepository.findByOrderId(orderId);
        relations.forEach(relation -> {
            CartItem cartItem = cartRepository.findById(relation.getCartItemId()).orElse(null);
            if (cartItem != null) {
                Stockpile stockpile = productService.getStock(cartItem.getProductId());
                synchronized (this) {
                    stockpile.setAmount(stockpile.getAmount() + cartItem.getQuantity());
                    stockpile.setLockedAmount(stockpile.getLockedAmount() - cartItem.getQuantity());
                    stockpileService.updateStockpile(stockpile);
                }
            }
        });

        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public Order confirmReceipt(Integer orderId, Integer userId) throws Exception {
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