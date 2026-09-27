package com.sportsbetting.settlement.domain.bet.api;

import com.sportsbetting.settlement.domain.bet.model.Bet;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BetMapper {
    BetView toView(Bet bet);
}
