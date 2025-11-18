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
public ResponseEntity<Map<String, Object>> createUpiPayment(@RequestBody Map<String, Object> req) {
    Long orderId = Long.valueOf(req.get("orderId").toString());
    Orders order = orderRepo.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found"));

    double amount = order.getTotal();
    String email = order.getUser().getEmail();
    String name = order.getUser().getName();
    String phone = order.getUser().getPhone();

    CreateOrderResult result = cashfreeService.createOrder(orderId, amount, email, name, phone);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("orderId", result.orderId);
    response.put("amount", amount);
    response.put("qrCodeUrl", result.qrCodeUrl);
    response.put("paymentSessionId", result.paymentSessionId);

    log.info("UPI Payment Ready → Order: {}, QR: {}", result.orderId, result.qrCodeUrl);
    return ResponseEntity.ok(response);
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