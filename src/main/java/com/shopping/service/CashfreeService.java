package com.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CashfreeService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CashfreeConfig cashfreeConfig;

    public static class CreateOrderResult {
        public String orderId;
        public Double amount;
        public String qrCodeUrl;
        public String paymentSessionId;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = cashfreeConfig.getAppId() + ":" + cashfreeConfig.getSecretKey();
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        headers.set("Authorization", "Basic " + encoded);
        headers.set("x-api-version", "2023-08-01");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amount, String email, String name, String phone) {
        if (phone == null || !phone.matches("^\\d{10}$")) {
            throw new IllegalArgumentException("Phone must be 10 digits");
        }

        String url = cashfreeConfig.getBaseUrl() + "/pg/orders";
        String orderId = "ORD_" + dbOrderId;

        Map<String, Object> body = new HashMap<>();
        body.put("order_id", orderId);
        body.put("order_amount", amount);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "cust_" + dbOrderId);
        customer.put("customer_name", name != null ? name : "Customer");
        customer.put("customer_email", email != null ? email : "user@example.com");
        customer.put("customer_phone", phone);
        body.put("customer_details", customer);

        Map<String, Object> meta = new HashMap<>();
        meta.put("return_url", "https://jayshopy-ma48.vercel.app/order-success?order_id={order_id}");
        meta.put("notify_url", "https://jayshoppy3-backend-1.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", meta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        log.info("Creating Cashfree Order → URL: {}, OrderId: {}, Amount: {}", url, orderId, amount);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            log.info("Cashfree Response: {}", response.getBody());

            CreateOrderResult result = new CreateOrderResult();
            result.orderId = root.path("order_id").asText();
            result.amount = amount;
            result.paymentSessionId = root.path("payment_session_id").asText();

            // BEST QR CODE LOGIC (CASHFREE → FALLBACK → UPI INTENT)
            String qr = root.path("payments").path("url").asText();
            if (qr == null || qr.isEmpty()) {
                qr = root.path("payment_link").asText();
            }

            // If Cashfree doesn't give QR → Generate UPI Intent QR
            if (qr == null || qr.isEmpty()) {
                String merchantUpi = cashfreeConfig.getMerchantUpiId();
                if (merchantUpi != null && !merchantUpi.trim().isEmpty()) {
                    String upiLink = String.format(
                        "upi://pay?pa=%s&pn=JayShoppy&am=%.2f&cu=INR&tr=%s&tn=Order Payment",
                        merchantUpi, amount, orderId
                    );
                    qr = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" +
                         URLEncoder.encode(upiLink, StandardCharsets.UTF_8);
                }
            }

            result.qrCodeUrl = qr;
            return result;

        } catch (Exception e) {
            log.error("Cashfree order creation failed for order {}", dbOrderId, e);
            throw new RuntimeException("Payment failed. Please try again.");
        }
    }

    // Webhook Signature Verification
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        try {
            String data = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(cashfreeConfig.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(hash);
            return computed.equals(signature);
        } catch (Exception e) {
            log.error("Webhook signature verification failed", e);
            return false;
        }
    }
}