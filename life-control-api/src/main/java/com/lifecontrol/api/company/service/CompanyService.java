package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.UUID;

@Service
public class CompanyService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserContext currentUserContext;

    public CompanyService(CompanyRepository companyRepository,
                          ApplicationEventPublisher eventPublisher,
                          CurrentUserContext currentUserContext) {
        this.companyRepository = companyRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserContext = currentUserContext;
    }

    @Transactional(readOnly = true)
    public Page<CompanyResponse> getAllCompanies(Pageable pageable, String search) {
        Page<Company> companies;

        if (!currentUserContext.isAdmin()) {
            // Country-role user: filter by assigned company IDs
            Set<UUID> companyIds = currentUserContext.getCompanyIds();
            if (companyIds.isEmpty()) {
                return Page.empty(pageable);
            }
            if (StringUtils.hasText(search)) {
                companies = companyRepository.findBySearchTermAndIdIn(search.trim(), companyIds, pageable);
            } else {
                companies = companyRepository.findAllByIdIn(companyIds, pageable);
            }
        } else if (StringUtils.hasText(search)) {
            companies = companyRepository.findBySearchTerm(search.trim(), pageable);
        } else {
            companies = companyRepository.findAll(pageable);
        }

        return companies.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CompanyResponse getCompanyById(UUID id) {
        currentUserContext.verifyCompanyAccess(id);
        return companyRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new CompanyNotFoundException(id));
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        // Validate uniqueness
        if (companyRepository.existsByCompanyKey(request.companyKey())) {
            throw new DuplicateCompanyException("Ya existe una compañía con companyKey: " + request.companyKey());
        }

        if (companyRepository.existsByRfc(request.rfc())) {
            throw new DuplicateCompanyException("Ya existe una compañía con RFC: " + request.rfc());
        }

        // Build entity
        Company company = Company.builder()
                .companyKey(request.companyKey())
                .companyName(request.companyName())
                .tipoPersonaId(request.tipoPersonaId())
                .razonSocial(request.razonSocial())
                .rfc(request.rfc())
                .phone(request.phone())
                .email(request.email())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        Company saved = companyRepository.save(company);

        eventPublisher.publishEvent(new CompanyCreatedEvent(this, saved.getId(), saved.getCompanyKey(), saved.getCompanyName()));
        logger.info("Company created and event published: id={}, companyKey={}, name={}",
                saved.getId(), saved.getCompanyKey(), saved.getCompanyName());

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

        // Update fields (companyKey is immutable)
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
        
        logger.info("Company soft-deleted: id={}, companyKey={}, timestamp={}", 
                id, company.getCompanyKey(), java.time.LocalDateTime.now());
    }

    private CompanyResponse toResponse(Company company) {
        return new CompanyResponse(
                company.getId(),
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