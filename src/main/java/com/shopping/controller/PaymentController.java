package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import com.shopping.entity.Cart;
import com.shopping.entity.CartItem;
import com.shopping.entity.OrderItem;
import com.shopping.entity.Orders;
import com.shopping.repository.OrderRepository;
import com.shopping.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import com.shopping.service.CashfreeService;
import com.shopping.service.CashfreeService.CreateOrderResult;
import com.shopping.service.CartService;

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
    private final UserRepository userRepository;
    private final CartService cartService;
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

    // Add this new method for cards (same logic, but return payment_link)
    @PostMapping("/user/create-card-payment")
    public ResponseEntity<Map<String, Object>> createCardPayment(@RequestBody Map<String, Object> req) {
        Long orderId = Long.valueOf(req.get("orderId").toString());
        Orders order = orderRepo.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));

        double amount = order.getTotal();
        String email = order.getUser().getEmail();
        String name = order.getUser().getName();
        String phone = order.getUser().getPhone();

        CreateOrderResult result = cashfreeService.createOrder(orderId, amount, email, name, phone);

        if (result.paymentLink == null || result.paymentLink.isEmpty()) {
            throw new RuntimeException("Card payment not available. Try UPI.");
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("orderId", result.orderId);
        res.put("amount", amount);
        res.put("paymentLink", result.paymentLink);  // ← Main for cards
        res.put("paymentSessionId", result.paymentSessionId);

        log.info("Card Payment Ready → Order: {}, Link: {}", result.orderId, result.paymentLink);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/user/create-payment-session")
    public ResponseEntity<Map<String, Object>> createPaymentSession(
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            String email = userDetails.getUsername();
            com.shopping.entity.User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // amount
            double amount = Double.parseDouble(req.get("amount").toString());

            // shippingAddress could be an object; serialize to string
            Object shipping = req.get("shippingAddress");
            String shippingStr = shipping == null ? "" : objectMapper.writeValueAsString(shipping);

            Orders order = new Orders();
            order.setUser(user);
            order.setTotal(amount);
            order.setShippingAddress(shippingStr);
            order.setStatus("PENDING");
            order.setOrderDate(java.time.LocalDateTime.now());

            Cart cart = cartService.getCart(user.getId());
            if (cart != null && cart.getItems() != null && !cart.getItems().isEmpty()) {
                for (CartItem cartItem : cart.getItems()) {
                    OrderItem orderItem = new OrderItem();
                    orderItem.setOrder(order);
                    orderItem.setProduct(cartItem.getProduct());
                    orderItem.setQuantity(cartItem.getQuantity());
                    orderItem.setPrice(cartItem.getProduct().getPrice());
                    orderItem.setPriceAtPurchase(cartItem.getProduct().getPrice());
                    order.getItems().add(orderItem);
                }
            }

            order = orderRepo.save(order);

            CreateOrderResult result = cashfreeService.createOrder(
                    order.getId(), order.getTotal(), user.getEmail(), user.getName(), user.getPhone()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", order.getId());
            response.put("amount", order.getTotal());
            response.put("paymentSessionId", result.paymentSessionId);
            response.put("paymentLink", result.paymentLink);
            response.put("qrCodeUrl", result.qrCodeUrl);

            // Dynamically detect Cashfree environment from the configured Base URL
            String baseUrl = cashfreeConfig.getBaseUrl();
            String envMode = (baseUrl != null && baseUrl.toLowerCase().contains("sandbox")) ? "sandbox" : "production";
            response.put("environment", envMode);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error creating payment session", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/user/order-status/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable Long orderId) {
        Orders order = orderRepo.findById(orderId).orElseThrow(() -> new RuntimeException("Order not found"));

        // Fallback: Check Cashfree dynamically if order status is still PENDING
        if ("PENDING".equalsIgnoreCase(order.getStatus())) {
            try {
                String cashfreeOrderId = "ORD_" + orderId;
                JsonNode cfOrder = cashfreeService.getCashfreeOrder(cashfreeOrderId);
                if (cfOrder != null) {
                    String cfStatus = cfOrder.path("order_status").asText("");
                    log.info("Fetched real-time status from Cashfree for {}: {}", cashfreeOrderId, cfStatus);

                    if ("PAID".equalsIgnoreCase(cfStatus)) {
                        order.setStatus("PAID");
                        // Set transactionId to cf_order_id as fallback
                        order.setTransactionId(cfOrder.path("cf_order_id").asText(""));
                        orderRepo.save(order);

                        // Clear the user's cart
                        cartService.clearCart(order.getUser().getId());
                    } else if ("FAILED".equalsIgnoreCase(cfStatus) || 
                               "CANCELLED".equalsIgnoreCase(cfStatus) || 
                               "EXPIRED".equalsIgnoreCase(cfStatus) || 
                               "TERMINATED".equalsIgnoreCase(cfStatus) ||
                               "USER_DROPPED".equalsIgnoreCase(cfStatus) ||
                               "DROPPED".equalsIgnoreCase(cfStatus)) {
                        order.setStatus("FAILED");
                        orderRepo.save(order);
                    } else if ("ACTIVE".equalsIgnoreCase(cfStatus)) {
                        // If order is still ACTIVE on Cashfree, check if there was a failed payment attempt
                        JsonNode payments = cashfreeService.getCashfreePayments(cashfreeOrderId);
                        if (payments != null && payments.isArray() && payments.size() > 0) {
                            log.info("Cashfree payments list for order {}: {}", cashfreeOrderId, payments.toString());
                            JsonNode latestPayment = null;
                            long maxPaymentId = -1;
                            String maxTime = "";
                            for (JsonNode payment : payments) {
                                long paymentId = payment.path("cf_payment_id").asLong(-1);
                                String paymentTime = payment.path("payment_time").asText("");
                                if (latestPayment == null) {
                                    latestPayment = payment;
                                    maxPaymentId = paymentId;
                                    maxTime = paymentTime;
                                } else {
                                    if (paymentId > maxPaymentId) {
                                        maxPaymentId = paymentId;
                                        latestPayment = payment;
                                    } else if (paymentId == maxPaymentId) {
                                        if (paymentTime.compareTo(maxTime) > 0) {
                                            maxTime = paymentTime;
                                            latestPayment = payment;
                                        }
                                    }
                                }
                            }
                            if (latestPayment != null) {
                                String paymentStatus = latestPayment.path("payment_status").asText("");
                                log.info("Identified latest payment attempt status: {} (ID: {}, Time: {})", 
                                    paymentStatus, latestPayment.path("cf_payment_id").asText(""), maxTime);
                                if ("FAILED".equalsIgnoreCase(paymentStatus) || 
                                    "USER_DROPPED".equalsIgnoreCase(paymentStatus) || 
                                    "CANCELLED".equalsIgnoreCase(paymentStatus) ||
                                    "DROPPED".equalsIgnoreCase(paymentStatus) ||
                                    "EXPIRED".equalsIgnoreCase(paymentStatus) ||
                                    "TERMINATED".equalsIgnoreCase(paymentStatus) ||
                                    "VOID".equalsIgnoreCase(paymentStatus)) {
                                    order.setStatus("FAILED");
                                    order.setTransactionId(latestPayment.path("cf_payment_id").asText(""));
                                    orderRepo.save(order);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve order status from Cashfree dynamically", e);
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", order.getStatus());
        res.put("transactionId", order.getTransactionId() == null ? "" : order.getTransactionId());
        res.put("total", order.getTotal());
        res.put("shippingAddress", order.getShippingAddress());
        res.put("orderDate", order.getOrderDate() != null ? order.getOrderDate().toString() : "");
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

                // Clear the user's cart
                cartService.clearCart(order.getUser().getId());
            } else if ("FAILED".equals(normalized) || 
                       "CANCELLED".equals(normalized) || 
                       "EXPIRED".equals(normalized) || 
                       "TERMINATED".equals(normalized) || 
                       "USER_DROPPED".equals(normalized) || 
                       "DROPPED".equals(normalized)) {
                order.setStatus("FAILED");
                orderRepo.save(order);
                log.info("Order {} marked FAILED/CANCELLED/DROPPED (status {})", dbOrderId, normalized);
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