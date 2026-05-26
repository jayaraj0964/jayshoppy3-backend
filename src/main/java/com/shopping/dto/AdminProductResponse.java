package com.shopping.dto;

import com.shopping.entity.Product;
import java.util.List;
import java.util.stream.Collectors;

public record AdminProductResponse(
    Long id,
    String name,
    String description,
    Double price,
    Integer stock,
    String category,
    String size,
    String color,
    String vendorName,
    String vendorShopName,
    String vendorPhone,
    Double costPrice,
    List<ProductImageResponse> images
) {
    public AdminProductResponse(Product product) {
        this(
            product.getId(),
            product.getName(),
            product.getDescription(),
            product.getPrice(),
            product.getStock(),
            product.getCategory(),
            product.getSize(),
            product.getColor(),
            product.getVendorName(),
            product.getVendorShopName(),
            product.getVendorPhone(),
            product.getCostPrice(),
            product.getImages() != null
                ? product.getImages().stream()
                    .map(ProductImageResponse::fromEntity)
                    .collect(Collectors.toList())
                : List.of()
        );
    }
}
