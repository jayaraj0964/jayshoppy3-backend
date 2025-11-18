package com.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

    // Response class
    public static class CreateOrderResult {
        public String orderId;
        public Double amount;
        public String qrCodeUrl;
        public String paymentSessionId;
    }

    // CORRECT HEADERS – 2025 LATEST METHOD (Basic Auth)
    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();

        // BASIC AUTH (MANDATORY SINCE 2024)
        String auth = cashfreeConfig.getAppId() + ":" + cashfreeConfig.getSecretKey();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        // Required headers
        headers.set("x-api-version", "2023-08-01");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));

        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amountInRupees, String email, String name, String phone, String returnUrl) {
        if (phone == null || !phone.matches("^\\d{10}$")) {
            throw new RuntimeException("Invalid phone number. Must be 10 digits.");
        }

        String url = cashfreeConfig.getBaseUrl() + "/pg/orders";

        String orderId = "ORD_" + dbOrderId;

        Map<String, Object> body = new HashMap<>();
        body.put("order_id", orderId);
        body.put("order_amount", amountInRupees);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "user_" + dbOrderId);
        customer.put("customer_name", name != null ? name : "Customer");
        customer.put("customer_email", email != null ? email : "customer@example.com");
        customer.put("customer_phone", phone);
        body.put("customer_details", customer);

        Map<String, Object> orderMeta = new HashMap<>();
        orderMeta.put("return_url", "https://jayshopy-ma48.vercel.app/order-success?order_id={order_id}");
        orderMeta.put("notify_url", "https://jayshoppy3-backend-1.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", orderMeta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        log.info("Creating Cashfree Order → URL: {}, OrderId: {}", url, orderId);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Cashfree failed: {} {}", response.getStatusCode(), response.getBody());
                throw new RuntimeException("Payment gateway error");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            log.info("Cashfree Success: {}", response.getBody());

            CreateOrderResult result = new CreateOrderResult();
            result.orderId = root.path("order_id").asText();
            result.amount = amountInRupees;
            result.paymentSessionId = root.path("payment_session_id").asText();

            // QR Code (for UPI Intent)
            String qr = root.path("payments").path("url").asText(null);
            if (qr == null || qr.isEmpty()) {
                qr = root.path("payment_link").asText(null);
            }
            result.qrCodeUrl = qr;

            return result;

        } catch (Exception e) {
            log.error("Cashfree order creation failed", e);
            throw new RuntimeException("Payment failed. Please try again.");
        }
    }

    // Overloaded method
    public CreateOrderResult createOrder(Long dbOrderId, double amountInRupees, String email, String name, String phone) {
        return createOrder(dbOrderId, amountInRupees, email, name, phone, null);
    }

    // Webhook signature verify (MANDATORY)
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
            log.error("Webhook verification failed", e);
            return false;
        }
    }
}