package com.lifecontrol.api.promotion.controller;

import com.lifecontrol.api.promotion.dto.PromotionRequest;
import com.lifecontrol.api.promotion.dto.PromotionResponse;
import com.lifecontrol.api.promotion.service.PromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/promotions")
@Tag(name = "Promotion Management", description = "API for managing promotions and discounts")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get all promotions", description = "Returns a paginated list of promotions")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of promotions")
    })
    public ResponseEntity<Page<PromotionResponse>> getAllPromotions(
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(promotionService.getAllPromotions(pageable));
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get active promotions", description = "Returns promotions currently active for the given sales channel")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of active promotions")
    })
    public ResponseEntity<List<PromotionResponse>> getActivePromotions(
            @RequestParam String channel) {
        return ResponseEntity.ok(promotionService.findActivePromotions(channel));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get promotion by ID", description = "Returns a single promotion")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Promotion found"),
        @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<PromotionResponse> getPromotionById(@PathVariable UUID id) {
        return ResponseEntity.ok(promotionService.getPromotionById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Create a promotion", description = "Creates a new promotion")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Promotion created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<PromotionResponse> createPromotion(
            @Valid @RequestBody PromotionRequest request) {
        var response = promotionService.createPromotion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Update a promotion", description = "Updates an existing promotion")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Promotion updated"),
        @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<PromotionResponse> updatePromotion(
            @PathVariable UUID id,
            @Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(promotionService.updatePromotion(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Delete a promotion", description = "Soft-deletes a promotion")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Promotion deleted"),
        @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<Void> deletePromotion(@PathVariable UUID id) {
        promotionService.deletePromotion(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Re-enable a promotion", description = "Re-enables a soft-deleted promotion")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Promotion re-enabled"),
        @ApiResponse(responseCode = "404", description = "Promotion not found")
    })
    public ResponseEntity<PromotionResponse> enablePromotion(@PathVariable UUID id) {
        return ResponseEntity.ok(promotionService.enablePromotion(id));
    }
}
