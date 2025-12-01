package com.lifecontrol.product.service;

import com.lifecontrol.product.dto.ProductRequest;
import com.lifecontrol.product.dto.ProductResponse;
import com.lifecontrol.product.model.Product;
import com.lifecontrol.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
  private final ProductRepository productRepository;

  public ProductResponse createProduct(ProductRequest productRequest) {
    Product product = Product.builder()
        .name(productRequest.name())
        .description(productRequest.description())
        .price(productRequest.price())
        .build();
    productRepository.save(product);
    log.info("Product created succesfully");
    return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice());
  }

  public List<ProductResponse> getAllProducts() {
    log.info("Product created succesfully");
    return productRepository.findAll()
        .stream()
        .map(this::getProductResponse)
        .toList();
  }

  public ProductResponse findProductById(String id) {
    log.info("Product by id" + id);
    Product product = productRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Producto no encontrado con ID: " + id));
    return this.getProductResponse(product);
  }

  private ProductResponse getProductResponse(Product product) {
    return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice());
  }
}
