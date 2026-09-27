package com.sportsbetting.settlement.domain.bet.repository;

import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.domain.bet.model.BetStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;

public interface BetRepository extends JpaRepository<Bet, String> {
    List<Bet> findByEventIdAndStatus(String eventId, BetStatus status);

    List<Bet> findByEventIdAndStatus(String eventId, BetStatus status, Pageable pageable);

    @Query("select distinct b.eventId from Bet b where b.status = :status and exists "
            + "(select o from RecordedOutcome o where o.eventId = b.eventId) order by b.eventId")
    List<String> findRecordedEventsWithBets(BetStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bet b where b.betId = :id")
    Optional<Bet> lockById(String id);
}
