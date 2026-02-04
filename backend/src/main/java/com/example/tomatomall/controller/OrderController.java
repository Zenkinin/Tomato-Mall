package com.example.tomatomall.controller;

import com.alibaba.fastjson.JSONObject;
import com.alipay.easysdk.factory.Factory;
import com.example.tomatomall.config.AliPayConfig;
import com.example.tomatomall.dto.PaymentNotifyDTO;
import com.example.tomatomall.po.Order;
import com.example.tomatomall.service.OrderService;
import com.example.tomatomall.util.JwtTokenUtil;
import com.example.tomatomall.vo.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class OrderController {

    @Resource
    AliPayConfig aliPayConfig;

    @Value("${alipay.returnUrl}")
    private String alipayReturnUrl;

    private static final String GATEWAY_URL ="https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String FORMAT ="JSON";
    private static final String CHARSET ="utf-8";
    private static final String SIGN_TYPE ="RSA2";

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

//    // [修改点] PathVariable 类型改为 Long
//    @PostMapping("/{orderId}/pay")
//    public void pay(@PathVariable Long orderId,
//                    @RequestParam(value = "token", required = false) String tokenParam,
//                    @RequestParam(value = "authorization", required = false) String authParam,
//                    HttpServletRequest request,
//                    HttpServletResponse response) throws Exception {
//
//        // --- 1. 认证逻辑保持不变 ---
//        String token = null;
//        String authHeader = request.getHeader("Authorization");
//        if (authHeader != null && authHeader.startsWith("Bearer ")) {
//            token = authHeader.substring(7);
//        } else if (authParam != null && authParam.startsWith("Bearer ")) {
//            token = authParam.substring(7);
//        } else if (tokenParam != null) {
//            token = tokenParam;
//        }
//
//        if (token != null) {
//            try {
//                Integer userId = jwtTokenUtil.getUserIdFromToken(token);
//                request.setAttribute("userId", userId);
//            } catch (Exception e) {
//                log.error("token验证失败", e);
//                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                response.getWriter().write("认证失败");
//                return;
//            }
//        } else {
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return;
//        }
//
//        // --- 2. 业务逻辑 ---
//
//        // [修改点] getOrderById 参数已在 Service 接口变更为 Long
//        Order order = orderService.getOrderById(orderId);
//
//        log.info("支付订单信息: ID={}, 状态={}, 创建时间={}", order.getOrderId(), order.getStatus(), order.getCreateTime());
//
//        if (order.getLockExpireTime().before(new Date())) {
//            throw new RuntimeException("订单已过期，请重新下单");
//        }
//
//        AlipayClient alipayClient = new DefaultAlipayClient(
//                GATEWAY_URL,
//                aliPayConfig.getAppId(),
//                aliPayConfig.getAppPrivateKey(),
//                FORMAT,
//                CHARSET,
//                aliPayConfig.getAlipayPublicKey(),
//                SIGN_TYPE);
//
//        String returnUrlWithToken = "http://localhost:8080/api/orders/payment-success?orderId=" + orderId;
//        if (token != null) {
//            returnUrlWithToken += "&token=" + token;
//        }
//
//        AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
//        alipayRequest.setNotifyUrl(aliPayConfig.getNotifyUrl());
//        alipayRequest.setReturnUrl(returnUrlWithToken);
//
//        JSONObject bizContent = new JSONObject();
//
//        // [修改点] 既然使用了雪花算法(Long)，ID本身就是全局唯一的，不需要再拼接时间戳了！
//        // 直接转成字符串传给支付宝
//        bizContent.put("out_trade_no", order.getOrderId().toString());
//
//        bizContent.put("total_amount", order.getTotalAmount());
//        bizContent.put("subject", "Tomato Mall Order #" + orderId);
//        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
//        alipayRequest.setBizContent(bizContent.toString());
//
//        String form = alipayClient.pageExecute(alipayRequest).getBody();
//
//        response.setContentType("text/html;charset=" + CHARSET);
//        response.getWriter().write(form);
//        response.getWriter().flush();
//        response.getWriter().close();
//    }

    //简化支付逻辑
    // [核心修改] 模拟支付接口：直接修改订单状态
    @PostMapping("/{orderId}/pay")
    public Response<String> pay(@PathVariable Long orderId,
                                @RequestAttribute("userId") Integer userId) {
        try {
            // 1. 验证订单归属
            Order order = orderService.getOrderById(orderId);
            if (order == null) {
                return Response.buildFailure("订单不存在", "404");
            }
            if (!order.getUserId().equals(userId)) {
                return Response.buildFailure("无权操作此订单", "403");
            }

            // 2. 验证状态
            if (!"PENDING".equals(order.getStatus())) {
                return Response.buildFailure("订单状态已更新，请勿重复支付", "400");
            }

            // 3. 检查过期
            if (order.getLockExpireTime().before(new Date())) {
                return Response.buildFailure("订单已过期，无法支付", "400");
            }

            // 4. [模拟支付成功] 修改状态
            order.setStatus("PAID");
            order.setPaymentTime(new Timestamp(System.currentTimeMillis()));
            // 模拟一个流水号
            order.setTradeNo("MOCK_" + System.currentTimeMillis());

            orderService.updateOrder(order);

            // 5. (可选) 如果你想测试 RocketMQ 的发货流程，可以在这里发一条消息
            // PaymentNotifyDTO notify = new PaymentNotifyDTO();
            // notify.setOutTradeNo(String.valueOf(orderId));
            // notify.setTotalAmount(order.getTotalAmount());
            // rocketMQTemplate.convertAndSend("payment-success-topic", notify);

            log.info("模拟支付成功，订单ID: {}", orderId);
            return Response.buildSuccess("支付成功");

        } catch (Exception e) {
            log.error("模拟支付失败", e);
            return Response.buildFailure("系统错误", "500");
        }
    }

    @PostMapping("/notify")
    public void payNotify(HttpServletRequest request, HttpServletResponse response) throws Exception {
        log.info("支付宝支付回调开始");

        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));

        boolean verifyResult = Factory.Payment.Common().verifyNotify(params);
        if (!verifyResult) {
            log.warn("签名验证失败");
            response.getWriter().print("fail");
            return;
        }

        String tradeStatus = params.get("trade_status");
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            response.getWriter().print("success");
            return;
        }

        PaymentNotifyDTO notifyDTO = new PaymentNotifyDTO();

        // [修改点] 直接获取 out_trade_no，不需要再进行字符串截取了
        // 因为我们在 pay 接口里传的就是纯粹的 orderId
        notifyDTO.setOutTradeNo(params.get("out_trade_no"));

        notifyDTO.setTradeNo(params.get("trade_no"));
        notifyDTO.setTotalAmount(new BigDecimal(params.get("total_amount")));

        // 发送 RocketMQ 消息
        rocketMQTemplate.convertAndSend("payment-success-topic", notifyDTO);
        log.info("RocketMQ 消息发送成功: {}", notifyDTO);

        response.getWriter().print("success");
    }

    // [修改点] PathVariable 改为 Long
    @GetMapping("/{orderId}/status")
    public Response<Order> checkOrderStatus(@PathVariable Long orderId) {
        return Response.buildSuccess(orderService.getOrderById(orderId));
    }

    @GetMapping
    public Response<List<Order>> getUserOrders(@RequestAttribute("userId") Integer userId) {
        try {
            List<Order> orders = orderService.getOrdersByUserId(userId);
            return Response.buildSuccess(orders);
        } catch (Exception e) {
            return Response.buildFailure("获取订单列表失败", "500");
        }
    }

    // [修改点] PathVariable 改为 Long
    @PostMapping("/{orderId}/cancel")
    public Response<Order> cancelOrder(
            @PathVariable Long orderId,
            @RequestAttribute("userId") Integer userId) {
        try {
            Order cancelledOrder = orderService.cancelOrder(orderId, userId);
            return Response.buildSuccess(cancelledOrder);
        } catch (Exception e) {
            return Response.buildFailure(e.getMessage(), "400");
        }
    }

    // [修改点] PathVariable 改为 Long
    @PostMapping("/{orderId}/confirm")
    public Response<Order> confirmReceipt(
            @PathVariable Long orderId,
            @RequestAttribute("userId") Integer userId) {
        try {
            Order confirmedOrder = orderService.confirmReceipt(orderId, userId);
            return Response.buildSuccess(confirmedOrder);
        } catch (Exception e) {
            return Response.buildFailure(e.getMessage(), "400");
        }
    }

    // [修改点] PathVariable 改为 Long
    @PostMapping("/{orderId}/check-payment")
    public Response<Order> checkPaymentManually(
            @PathVariable Long orderId,
            @RequestAttribute(value = "userId", required = false) Integer userId) {
        try {
            Order order = orderService.getOrderById(orderId);
            if (order == null) return Response.buildFailure("订单不存在", "404");

            // 权限检查
            if (userId != null && !order.getUserId().equals(userId)) {
                return Response.buildFailure("无权访问", "403");
            }

            if (!"PENDING".equals(order.getStatus())) {
                return Response.buildSuccess(order);
            }

            // 主动查询支付宝
            boolean isPaid = orderService.checkPaymentStatusWithAlipay(orderId);
            if (isPaid) {
                order.setStatus("PAID");
                order.setPaymentTime(new Timestamp(new Date().getTime()));
                order = orderService.updateOrder(order);
            }
            return Response.buildSuccess(order);
        } catch (Exception e) {
            log.error("检查支付状态失败", e);
            return Response.buildFailure("系统错误", "500");
        }
    }

    // 添加支付成功后的自动关闭页面
    @GetMapping("/payment-success")
    public void paymentSuccess(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String orderId = request.getParameter("orderId");

        // 输出自动关闭页面
        response.setContentType("text/html;charset=utf-8");
        PrintWriter writer = response.getWriter();
        writer.write("<!DOCTYPE html>");
        writer.write("<html lang='zh-CN'>");
        writer.write("<head><meta charset='utf-8'><title>支付成功</title></head>");
        writer.write("<body>");
        writer.write("<div style='text-align:center;padding:40px;'>");
        writer.write("<h2 style='color:#67C23A'>支付已完成！</h2>");
        writer.write("<p>订单号: " + orderId + "</p>");
        writer.write("<p>页面将在<span id='countdown'>5</span>秒后自动关闭...</p>");
        writer.write("</div>");
        writer.write("<script>");
        // 倒计时并通知父窗口
        writer.write("let seconds = 5;");
        writer.write("const timer = setInterval(() => {");
        writer.write("  seconds--;");
        writer.write("  document.getElementById('countdown').textContent = seconds;");
        writer.write("  if (seconds <= 0) {");
        writer.write("    clearInterval(timer);");
        writer.write("    try {");
        writer.write("      window.opener.postMessage({type: 'PAYMENT_COMPLETE', orderId: '" + orderId + "'}, '*');");
        writer.write("    } catch(e) { console.error('无法通知父窗口', e); }");
        writer.write("    window.close();");
        writer.write("  }");
        writer.write("}, 1000);");
        writer.write("</script>");
        writer.write("</body></html>");
        writer.flush();
    }
}