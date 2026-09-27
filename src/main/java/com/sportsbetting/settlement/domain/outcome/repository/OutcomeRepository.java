package com.sportsbetting.settlement.domain.outcome.repository;

import com.sportsbetting.settlement.domain.outcome.model.RecordedOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutcomeRepository extends JpaRepository<RecordedOutcome, String> {}
