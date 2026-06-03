package com.lifecontrol.api.measureunit.controller;

import com.lifecontrol.api.measureunit.dto.MeasureUnitRequest;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.measureunit.service.MeasureUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/measure-units")
@Tag(name = "Measure Unit Management", description = "API for managing measure units (SAT catalog)")
public class MeasureUnitController {

    private final MeasureUnitService measureUnitService;

    public MeasureUnitController(MeasureUnitService measureUnitService) {
        this.measureUnitService = measureUnitService;
    }

    @GetMapping
    @Operation(summary = "Get all measure units", description = "Returns a list of measure units. Use ?includeDisabled=true to include soft-deleted units.")
    public ResponseEntity<List<MeasureUnitResponse>> getAllMeasureUnits(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(measureUnitService.getAllMeasureUnits(includeDisabled));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get measure unit by ID", description = "Returns a single measure unit by its UUID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Measure unit found"),
        @ApiResponse(responseCode = "404", description = "Measure unit not found")
    })
    public ResponseEntity<MeasureUnitResponse> getMeasureUnitById(@PathVariable UUID id) {
        return ResponseEntity.ok(measureUnitService.getMeasureUnitById(id));
    }

    @GetMapping("/type/{unitType}")
    @Operation(summary = "Get measure units by type", description = "Returns enabled measure units filtered by type (PRODUCT or SERVICE)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Measure units found"),
        @ApiResponse(responseCode = "400", description = "Invalid unit type")
    })
    public ResponseEntity<List<MeasureUnitResponse>> getMeasureUnitsByType(@PathVariable String unitType) {
        return ResponseEntity.ok(measureUnitService.getMeasureUnitsByType(unitType));
    }

    @PostMapping
    @Operation(summary = "Create a new measure unit", description = "Creates a new measure unit with the provided details")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Measure unit created"),
        @ApiResponse(responseCode = "400", description = "Invalid input or unit type"),
        @ApiResponse(responseCode = "409", description = "Measure unit with same SAT code already exists")
    })
    public ResponseEntity<MeasureUnitResponse> createMeasureUnit(@Valid @RequestBody MeasureUnitRequest request) {
        var response = measureUnitService.createMeasureUnit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a measure unit", description = "Updates an existing measure unit")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Measure unit updated"),
        @ApiResponse(responseCode = "400", description = "Invalid input or unit type"),
        @ApiResponse(responseCode = "404", description = "Measure unit not found"),
        @ApiResponse(responseCode = "409", description = "SAT code already in use by another unit")
    })
    public ResponseEntity<MeasureUnitResponse> updateMeasureUnit(
            @PathVariable UUID id,
            @Valid @RequestBody MeasureUnitRequest request) {
        return ResponseEntity.ok(measureUnitService.updateMeasureUnit(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a measure unit", description = "Soft-deletes a measure unit by setting enabled to false")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Measure unit deleted"),
        @ApiResponse(responseCode = "404", description = "Measure unit not found")
    })
    public ResponseEntity<Void> deleteMeasureUnit(@PathVariable UUID id) {
        measureUnitService.deleteMeasureUnit(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable a measure unit", description = "Re-enables a soft-deleted measure unit")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Measure unit enabled"),
        @ApiResponse(responseCode = "404", description = "Measure unit not found")
    })
    public ResponseEntity<MeasureUnitResponse> enableMeasureUnit(@PathVariable UUID id) {
        return ResponseEntity.ok(measureUnitService.enableMeasureUnit(id));
    }
}
