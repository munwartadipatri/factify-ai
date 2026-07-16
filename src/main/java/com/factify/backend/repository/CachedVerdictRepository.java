package com.factify.backend.repository;

import com.factify.backend.domain.entity.CachedVerdict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CachedVerdictRepository extends JpaRepository<CachedVerdict, UUID> {
}
