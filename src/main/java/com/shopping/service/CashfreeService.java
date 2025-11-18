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

    public static class CreateOrderResult {
        public String orderId;
        public Double amount;
        public String qrCodeUrl;
        public String paymentSessionId;  // ← NEW
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", cashfreeConfig.getAppId());
        headers.set("x-client-secret", cashfreeConfig.getSecretKey());
        headers.set("x-api-version", "2023-08-01");
        headers.setAccept(MediaType.parseMediaTypes("application/json"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amountInRupees, String email, String name, String phone, String returnUrl) {
        if (phone == null || !phone.matches("^\\d{10}$")) {
            throw new RuntimeException("Missing or invalid customer phone. Provide a 10 digit phone number for the customer.");
        }

        String base = cashfreeConfig.getBaseUrl();
        if (!base.endsWith("/")) base = base + "/";
        String url = base + "pg/orders";

        Map<String, Object> body = new HashMap<>();
        String orderId = "ORD_" + dbOrderId;

        body.put("order_id", orderId);
        body.put("order_amount", amountInRupees);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "user_" + (dbOrderId == null ? "0" : dbOrderId));
        customer.put("customer_email", email == null ? "" : email);
        customer.put("customer_phone", phone);
        customer.put("customer_name", name == null ? "" : name);
        body.put("customer_details", customer);

        Map<String, Object> orderMeta = new HashMap<>();
        if (returnUrl != null) orderMeta.put("return_url", returnUrl);
        orderMeta.put("return_url", "https://jayshopy.vercel.app/order-success?order_id={order_id}");
        orderMeta.put("notify_url", "https://jayshoppy3-backend-1.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", orderMeta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        try {
            log.info("Creating Cashfree order url={} headers={} body={}", url, getHeaders().toSingleValueMap(), objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.debug("Could not log request body: {}", e.getMessage());
        }

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            log.info("Cashfree createOrder response status={} body={}", resp.getStatusCodeValue(), resp.getBody());

            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Cashfree order creation failed: status=" + resp.getStatusCodeValue() + " body=" + resp.getBody());
            }

            String respBody = resp.getBody();
            JsonNode root = objectMapper.readTree(respBody);

            CreateOrderResult result = new CreateOrderResult();
            result.orderId = root.path("order_id").asText(orderId);
            result.amount = amountInRupees;
            result.paymentSessionId = root.path("payment_session_id").asText(null);  // ← ADDED

            // Optional: Check if Cashfree returns QR
            String upiUrl = root.path("upi_url").asText(null);
            String qrUrl = root.path("qr_code_url").asText(null);
            if (upiUrl != null && !upiUrl.isEmpty()) result.qrCodeUrl = upiUrl;
            else if (qrUrl != null && !qrUrl.isEmpty()) result.qrCodeUrl = qrUrl;
            else result.qrCodeUrl = null;

            return result;
        } catch (HttpClientErrorException httpEx) {
            String respBody = httpEx.getResponseBodyAsString();
            log.error("Cashfree HTTP error status={} body={}", httpEx.getStatusCode().value(), respBody);
            throw new RuntimeException("Cashfree create order failed: " + httpEx.getStatusCode() + " body=" + respBody, httpEx);
        } catch (RestClientException rce) {
            log.error("RestClientException creating Cashfree order", rce);
            throw new RuntimeException("Cashfree create order failed", rce);
        } catch (Exception e) {
            log.error("Error parsing Cashfree create order response", e);
            throw new RuntimeException("Invalid Cashfree response", e);
        }
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amountInRupees, String email, String name) {
        return createOrder(dbOrderId, amountInRupees, email, name, null, null);
    }

    // Optional: Dynamic QR (kept but not used in sandbox)
    public String generateDynamicQR(Long dbOrderId, double amountInRupees, int expirySeconds) {
        // ... (same as before, optional)
        throw new RuntimeException("Dynamic QR not supported in sandbox");
    }

    public boolean verifyWebhookSignature(String payload, String receivedSignature, String timestamp) {
        try {
            String secret = cashfreeConfig.getSecretKey();
            if (secret == null) {
                log.warn("Cashfree secret key not configured");
                return false;
            }
            String signed = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(signed.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(rawHmac);
            log.debug("Webhook signature computed={}, received={}", computed, receivedSignature);
            return computed.equals(receivedSignature);
        } catch (Exception e) {
            log.error("Webhook signature verification failed", e);
            return false;
        }
    }
}