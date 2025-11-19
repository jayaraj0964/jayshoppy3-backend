package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import com.shopping.entity.Orders;
import com.shopping.repository.OrderRepository;
import com.shopping.service.CashfreeService;
import com.shopping.service.CashfreeService.CreateOrderResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Slf4j
public class PaymentController {

    private final CashfreeService cashfreeService;
    private final OrderRepository orderRepo;
    private final CashfreeConfig cashfreeConfig;
    private final ObjectMapper objectMapper;
@PostMapping("/user/create-upi-payment")
public ResponseEntity<Map<String, Object>> createUpiOnlyPayment(@RequestBody Map<String, Object> req) {
    Long orderId = Long.valueOf(req.get("orderId").toString());
    Orders order = orderRepo.findById(orderId).orElseThrow();

    CreateOrderResult result = cashfreeService.createOrder(
        orderId, order.getTotal(), order.getUser().getEmail(),
        order.getUser().getName(), order.getUser().getPhone()
    );

    // Force UPI QR (even if Cashfree doesn't give, generate fallback)
    String finalQr = result.qrCodeUrl;
    if (finalQr == null || finalQr.isEmpty()) {
        String merchantUpi = cashfreeConfig.getMerchantUpiId();
        if (merchantUpi != null && !merchantUpi.isEmpty()) {
            String upiLink = String.format("upi://pay?pa=%s&pn=JayShoppy&am=%.2f&cu=INR&tr=%s",
                merchantUpi, order.getTotal(), result.orderId);
            finalQr = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" +
                      URLEncoder.encode(upiLink, StandardCharsets.UTF_8);
        }
    }

    Map<String, Object> res = new HashMap<>();
    res.put("qrCodeUrl", finalQr);
    res.put("orderId", result.orderId);
    res.put("amount", order.getTotal());
    return ResponseEntity.ok(res);
}

@PostMapping("/user/create-card-payment")
public ResponseEntity<Map<String, Object>> createCardPayment(@RequestBody Map<String, Object> req) {
    Long orderId = Long.valueOf(req.get("orderId").toString());
    Orders order = orderRepo.findById(orderId).orElseThrow();

    CreateOrderResult result = cashfreeService.createOrder(
        orderId, order.getTotal(), order.getUser().getEmail(),
        order.getUser().getName(), order.getUser().getPhone()
    );

    if (result.paymentLink == null || result.paymentLink.isEmpty()) {
        throw new RuntimeException("Card payment not available. Try UPI.");
    }

    Map<String, Object> res = new HashMap<>();
    res.put("paymentLink", result.paymentLink);
    res.put("orderId", result.orderId);
    res.put("amount", order.getTotal());
    return ResponseEntity.ok(res);
}

    @GetMapping("/user/order-status/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable Long orderId) {
        Orders order = orderRepo.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));
        Map<String, Object> res = new HashMap<>();
        res.put("status", order.getStatus());
        res.put("transactionId", order.getTransactionId() == null ? "" : order.getTransactionId());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/user/webhook/cashfree")
    public ResponseEntity<String> cashfreeWebhook(
            @RequestBody String rawPayload,
            @RequestHeader("x-webhook-signature") String signature,
            @RequestHeader("x-webhook-timestamp") String timestamp) {

        if (!cashfreeService.verifyWebhookSignature(rawPayload, signature, timestamp)) {
            log.warn("Invalid Cashfree webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String cashfreeOrderId = payload.path("order_id").asText(null);
            String status = payload.path("order").path("order_status").asText(null);

            if (cashfreeOrderId == null) {
                log.warn("Webhook missing order_id");
                return ResponseEntity.ok("IGNORED");
            }

            Long dbOrderId = Long.parseLong(cashfreeOrderId.replaceAll("^ORD_", ""));
            Orders order = orderRepo.findById(dbOrderId).orElse(null);

            if (order == null) {
                log.warn("Webhook for unknown order {}", dbOrderId);
                return ResponseEntity.ok("IGNORED");
            }

            String normalized = status == null ? "" : status.toUpperCase();

            if ("PAID".equals(normalized) && "PENDING".equalsIgnoreCase(order.getStatus())) {
                order.setStatus("PAID");
                String cfPaymentId = payload.path("payment").path("cf_payment_id").asText(null);
                order.setTransactionId(cfPaymentId);
                orderRepo.save(order);
                log.info("Order {} marked PAID (cfPaymentId={})", dbOrderId, cfPaymentId);
            } else if ("FAILED".equals(normalized) || "CANCELLED".equals(normalized)) {
                order.setStatus("FAILED");
                orderRepo.save(order);
                log.info("Order {} marked FAILED/CANCELLED (status {})", dbOrderId, normalized);
            } else {
                log.info("Received webhook for order {} with status {}", dbOrderId, normalized);
            }

            return ResponseEntity.ok("OK");
        } catch (Exception ex) {
            log.error("Error processing Cashfree webhook", ex);
            return ResponseEntity.status(500).body("ERROR");
        }
    }
}