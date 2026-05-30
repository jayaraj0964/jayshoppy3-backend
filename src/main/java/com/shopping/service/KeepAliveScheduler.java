package com.shopping.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class KeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveScheduler.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.keep-alive-url:https://jayshoppy3-backend-2.onrender.com/ping}")
    private String keepAliveUrl;

    // Run every 3 minutes using cron expression (at second 0, every 3rd minute)
    @Scheduled(cron = "0 */3 * * * *")
    public void pingSelf() {
        try {
            log.info("Sending self-keep-alive request to: {}", keepAliveUrl);
            String response = restTemplate.getForObject(keepAliveUrl, String.class);
            log.info("Self-keep-alive response: {}", response);
        } catch (Exception e) {
            log.error("Failed to execute self-keep-alive ping: {}", e.getMessage());
        }
    }
}
