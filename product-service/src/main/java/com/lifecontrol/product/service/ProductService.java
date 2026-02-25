package com.lifecontrol.product.service;

import com.lifecontrol.product.dto.ProductCreateRequest;
import com.lifecontrol.product.dto.ProductResponse;
import com.lifecontrol.product.dto.ProductUpdateRequest;
import com.lifecontrol.product.exception.ProductNotFoundException;
import com.lifecontrol.product.model.Product;
import com.lifecontrol.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .build();
        
        product = productRepository.save(product);
        log.info("Product created successfully with id: {}", product.getId());
        
        return getProductResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        log.info("Fetching all products");
        return productRepository.findAll()
                .stream()
                .map(this::getProductResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findProductById(UUID id) {
        log.info("Fetching product with id: {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Producto no encontrado con ID: " + id));
        return getProductResponse(product);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductUpdateRequest request) {
        log.info("Updating product with id: {}", id);
        
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Producto no encontrado con ID: " + id));
        
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        
        product = productRepository.save(product);
        log.info("Product updated successfully: {}", id);
        
        return getProductResponse(product);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        log.info("Deleting product with id: {}", id);
        productRepository.deleteById(id);
        log.info("Product deleted successfully: {}", id);
    }

    private ProductResponse getProductResponse(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice());
    }
}
