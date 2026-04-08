package com.lifecontrol.api.company.service;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CompanyService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

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
        if (companyRepository.existsByCompanyId(request.companyId())) {
            throw new DuplicateCompanyException("Ya existe una compañía con companyId: " + request.companyId());
        }

        if (companyRepository.existsByCompanyKey("COMPANY_" + request.companyId())) {
            throw new DuplicateCompanyException("Ya existe una compañía con ese companyKey");
        }

        if (companyRepository.existsByRfc(request.rfc())) {
            throw new DuplicateCompanyException("Ya existe una compañía con RFC: " + request.rfc());
        }

        // Build entity
        Company company = Company.builder()
                .companyId(request.companyId())
                .companyKey("COMPANY_" + request.companyId())
                .companyName(request.companyName())
                .tipoPersonaId(request.tipoPersonaId())
                .razonSocial(request.razonSocial())
                .rfc(request.rfc())
                .phone(request.phone())
                .email(request.email())
                .enabled(request.enabled() != null ? request.enabled() : true)
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
        if (companyRepository.existsByRfcAndIdNot(request.rfc(), id)) {
            throw new DuplicateCompanyException("Ya existe una compañía con RFC: " + request.rfc());
        }

        // Note: companyKey is derived from companyId which is immutable, so no validation needed
        // companyKey = "COMPANY_" + companyId (cannot change during update)

        // Update fields (companyId and companyKey are immutable)
        company.setCompanyName(request.companyName());
        company.setTipoPersonaId(request.tipoPersonaId());
        company.setRazonSocial(request.razonSocial());
        company.setRfc(request.rfc());
        company.setPhone(request.phone());
        company.setEmail(request.email());
        company.setEnabled(request.enabled() != null ? request.enabled() : true);

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
        return new CompanyResponse(
                company.getCompanyId(),
                company.getCompanyKey(),
                company.getCompanyName(),
                company.getTipoPersonaId(),
                company.getRazonSocial(),
                company.getRfc(),
                company.getPhone(),
                company.getEmail(),
                company.getEnabled(),
                company.getCreatedAt(),
                company.getUpdatedAt()
        );
    }
}