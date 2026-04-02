package com.lifecontrol.api.company.service;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

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