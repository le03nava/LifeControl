package com.lifecontrol.api.company.service;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;

    @Transactional(readOnly = true)
    public List<CompanyResponse> getAllCompanies() {
        return companyRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanyResponse getCompanyById(UUID id) {
        return companyRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new CompanyNotFoundException(id));
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        // Validate uniqueness
        if (companyRepository.existsByCompanyId(request.getCompanyId())) {
            throw new DuplicateCompanyException("Ya existe una compañía con companyId: " + request.getCompanyId());
        }

        if (companyRepository.existsByCompanyKey("COMPANY_" + request.getCompanyId())) {
            throw new DuplicateCompanyException("Ya existe una compañía con ese companyKey");
        }

        if (companyRepository.existsByRfc(request.getRfc())) {
            throw new DuplicateCompanyException("Ya existe una compañía con RFC: " + request.getRfc());
        }

        // Build entity
        Company company = Company.builder()
                .companyId(request.getCompanyId())
                .companyKey("COMPANY_" + request.getCompanyId())
                .companyName(request.getCompanyName())
                .tipoPersonaId(request.getTipoPersonaId())
                .razonSocial(request.getRazonSocial())
                .rfc(request.getRfc())
                .phone(request.getPhone())
                .email(request.getEmail())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();

        Company saved = companyRepository.save(company);

        return toResponse(saved);
    }

    @Transactional
    public CompanyResponse updateCompany(UUID id, CompanyRequest request) {
        // Fetch existing company
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new CompanyNotFoundException(id));

        // Validate uniqueness (excluding current company)
        if (companyRepository.existsByRfcAndIdNot(request.getRfc(), id)) {
            throw new DuplicateCompanyException("Ya existe una compañía con RFC: " + request.getRfc());
        }

        // Note: companyKey is derived from companyId which is immutable, so no validation needed
        // companyKey = "COMPANY_" + companyId (cannot change during update)

        // Update fields (companyId and companyKey are immutable)
        company.setCompanyName(request.getCompanyName());
        company.setTipoPersonaId(request.getTipoPersonaId());
        company.setRazonSocial(request.getRazonSocial());
        company.setRfc(request.getRfc());
        company.setPhone(request.getPhone());
        company.setEmail(request.getEmail());
        company.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);

        Company updated = companyRepository.save(company);

        return toResponse(updated);
    }

    @Transactional
    public void deleteCompany(UUID id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new CompanyNotFoundException(id));
        
        company.setEnabled(false);
        companyRepository.save(company);
        
        logger.info("Company soft-deleted: id={}, companyId={}, timestamp={}", 
                id, company.getCompanyId(), java.time.LocalDateTime.now());
    }

    private CompanyResponse toResponse(Company company) {
        return CompanyResponse.builder()
                .companyId(company.getCompanyId())
                .companyKey(company.getCompanyKey())
                .companyName(company.getCompanyName())
                .tipoPersonaId(company.getTipoPersonaId())
                .razonSocial(company.getRazonSocial())
                .rfc(company.getRfc())
                .phone(company.getPhone())
                .email(company.getEmail())
                .enabled(company.getEnabled())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }
}