// src/main/java/com/shopping/service/CashfreeService.java

package com.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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

    // FULLY UPDATED RESULT CLASS
    public static class CreateOrderResult {
        public String orderId;
        public Double amount;
        public String qrCodeUrl;
        public String paymentSessionId;
        public String paymentLink;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = cashfreeConfig.getAppId() + ":" + cashfreeConfig.getSecretKey();
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        headers.set("Authorization", "Basic " + encoded);
        headers.set("x-api-version", "2023-08-01");
        headers.set("x-client-id", cashfreeConfig.getAppId());
        headers.set("x-client-secret", cashfreeConfig.getSecretKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));

        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amount, String email, String name, String phone) {

        log.info("==================================================");
        log.info("CASHFREE ORDER CREATION STARTED");
        log.info("DB Order ID     : {}", dbOrderId);
        log.info("Amount          : ₹{}", amount);
        log.info("Customer        : {} ({})", name, email);
        log.info("Phone           : {}", phone);
        log.info("App ID          : {}", cashfreeConfig.getAppId());
        log.info("Secret Key (end): {}", 
                 cashfreeConfig.getSecretKey().length() > 10 
                 ? "..." + cashfreeConfig.getSecretKey().substring(cashfreeConfig.getSecretKey().length() - 10) 
                 : "HIDDEN");
        log.info("Base URL        : {}", cashfreeConfig.getBaseUrl());
        log.info("Environment     : {}", cashfreeConfig.getBaseUrl().contains("sandbox") ? "TEST" : "PRODUCTION");
        log.info("==================================================");

        if (phone == null || !phone.matches("^\\d{10}$")) {
            throw new IllegalArgumentException("Invalid phone number: must be 10 digits");
        }

        String url = cashfreeConfig.getBaseUrl() + "/pg/orders";
        String orderId = "ORD_" + dbOrderId;

        Map<String, Object> body = new HashMap<>();
        body.put("order_id", orderId);
        body.put("order_amount", amount);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "cust_" + dbOrderId);
        customer.put("customer_name", name != null && !name.isEmpty() ? name : "Jay Shoppy Customer");
        customer.put("customer_email", email != null && !email.isEmpty() ? email : "customer@example.com");
        customer.put("customer_phone", phone);
        body.put("customer_details", customer);

        Map<String, Object> meta = new HashMap<>();
        meta.put("return_url", "https://jayshopy-ma48.vercel.app/order-success?order_id={order_id}");
        meta.put("notify_url", "https://jayshoppy3-backend-1.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", meta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        log.info("REQUEST → POST {}", url);
        log.info("Request Body: {}", body);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            log.info("CASHFREE SUCCESS! Status: {}", response.getStatusCode());
            log.info("Full Response Body: {}", response.getBody());

            JsonNode root = objectMapper.readTree(response.getBody());

            CreateOrderResult result = new CreateOrderResult();
            result.orderId = root.path("order_id").asText();
            result.amount = amount;
            result.paymentSessionId = root.path("payment_session_id").asText();
            result.paymentLink = root.path("payment_link").asText();

            // QR Logic
            String qrCodeUrl = root.path("payments").path("url").asText();
            if (qrCodeUrl == null || qrCodeUrl.isEmpty()) {
                qrCodeUrl = root.path("payment_link").asText();
            }
            if (qrCodeUrl == null || qrCodeUrl.isEmpty()) {
                String vpa = cashfreeConfig.getMerchantUpiId();
                if (vpa != null && !vpa.trim().isEmpty()) {
                    String upiLink = String.format(
                        "upi://pay?pa=%s&pn=JayShoppy&am=%.2f&cu=INR&tr=%s",
                        vpa, amount, orderId
                    );
                    qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" +
                                URLEncoder.encode(upiLink, StandardCharsets.UTF_8);
                    log.info("Generated Fallback QR using VPA: {}", vpa);
                }
            }

            result.qrCodeUrl = qrCodeUrl;

            log.info("ORDER READY → ID: {} | QR: {} | Card Link: {}", 
                     result.orderId, 
                     result.qrCodeUrl != null, 
                     result.paymentLink != null);

            return result;

       } catch (HttpClientErrorException e) {
    // SUPER SAFE + DETAILED 401 LOGGING (NO NULL POINTER)
    log.error("==================================================");
    log.error("CASHFREE PAYMENT GATEWAY ERROR!");
    log.error("HTTP Status     : {}", e.getStatusCode());
    log.error("Error Message   : {}", e.getMessage());

    // SAFELY GET RESPONSE BODY (null check)
    String responseBody = e.getResponseBodyAsString();
    if (responseBody == null || responseBody.trim().isEmpty()) {
        responseBody = "[EMPTY OR NULL RESPONSE BODY]";
    }
    log.error("Response Body   : {}", responseBody);

    log.error("Request URL     : {}", url);
    log.error("App ID          : {}", cashfreeConfig.getAppId());
    log.error("Secret Key Last : ...{}", 
              cashfreeConfig.getSecretKey().substring(
                  Math.max(0, cashfreeConfig.getSecretKey().length() - 10)));

    // NOW SAFE TO LOWERCASE
    String bodyLower = responseBody.toLowerCase();

    if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        log.error("100% CONFIRMED → 401 UNAUTHORIZED (Authentication Failed)");

        if (bodyLower.contains("invalid") || bodyLower.contains("credential")) {
            log.error("REASON → App ID or Secret Key is incorrect / has extra spaces");
        } else if (bodyLower.contains("not active") || bodyLower.contains("disabled") || bodyLower.contains("production")) {
            log.error("REASON → Production API is DISABLED in Cashfree Dashboard!");
            log.error("FIX → Dashboard → Settings → API Keys → Turn ON 'Enable Production API'");
        } else if (bodyLower.contains("account") || bodyLower.contains("merchant")) {
            log.error("REASON → Merchant account not fully activated for live payments");
        } else {
            log.error("REASON → Unknown auth issue – most likely Production mode not enabled");
        }

        log.error("IMMEDIATE FIX → https://dashboard.cashfree.com → Settings → API Keys → Enable Production API");
    } else {
        log.error("Other HTTP Error (not 401) → {}", e.getStatusCode());
    }

    log.error("==================================================");

    throw new RuntimeException("Payment failed – check server authentication issue. Check logs.");
} catch (Exception e) {
            log.error("Exception during Cashfree order creation", e);
            throw new RuntimeException("Failed to create Cashfree order", e);
        }
    }

    // Webhook verification (unchanged)
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