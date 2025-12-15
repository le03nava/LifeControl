package com.lifecontrol.product.controller;

import com.lifecontrol.product.dto.ProductRequest;
import com.lifecontrol.product.dto.ProductResponse;
import com.lifecontrol.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

  private final ProductService productService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProductResponse createProduct(@RequestBody ProductRequest productRequest) {
    return productService.createProduct(productRequest);
  }

  @GetMapping
  @ResponseStatus(HttpStatus.OK)
  public List<ProductResponse> getAllProducts() {
    return productService.getAllProducts();
  }

  @GetMapping("/{id}")
  @ResponseStatus(HttpStatus.OK)
  public ProductResponse findProductById(@PathVariable String id) {
    return productService.findProductById(id);
  }

  @PutMapping
  @ResponseStatus(HttpStatus.OK)
  public ProductResponse updateProduct(@RequestBody ProductRequest productRequest) {
    return productService.updateProduct(productRequest);
  }
}
