// src/main/java/com/shopping/config/CashfreeConfig.java
package com.shopping.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix = "cashfree")
@Data
public class CashfreeConfig {
    private String appId;
    private String secretKey;
    private String baseUrl = "https://api.cashfree.com"; 
    private String mode = "PROD";
    private String returnUrl; 
    private String merchantUpiId;
    

    public String getBaseUrl() {
    if (baseUrl == null) return null;
    return baseUrl.trim(); 
}
}