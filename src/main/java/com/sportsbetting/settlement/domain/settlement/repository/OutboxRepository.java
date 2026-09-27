package com.sportsbetting.settlement.domain.settlement.repository;

import com.sportsbetting.settlement.domain.settlement.model.SettlementOutbox;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface OutboxRepository extends JpaRepository<SettlementOutbox, String> {
    @Query("select o.betId from SettlementOutbox o where o.sent = false and o.nextAttempt <= :now order by o.nextAttempt, o.betId")
    List<String> findDue(Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SettlementOutbox o where o.betId = :id")
    Optional<SettlementOutbox> lockById(String id);
}
