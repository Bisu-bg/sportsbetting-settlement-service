package com.sportsbetting.settlement.api;

import com.sportsbetting.settlement.support.BetFixtures;
import com.sportsbetting.settlement.api.ApiExceptionHandler;
import com.sportsbetting.settlement.domain.bet.api.BetController;
import com.sportsbetting.settlement.domain.bet.api.BetMapper;
import com.sportsbetting.settlement.domain.outcome.api.EventOutcome;
import com.sportsbetting.settlement.domain.outcome.api.OutcomeController;
import com.sportsbetting.settlement.domain.bet.model.Bet;
import com.sportsbetting.settlement.messaging.BrokerUnavailableException;
import com.sportsbetting.settlement.domain.outcome.messaging.OutcomePublisher;
import com.sportsbetting.settlement.domain.bet.service.BetService;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TestApi {
    private final BetService betService = mock(BetService.class);
    private final OutcomePublisher outcomePublisher = mock(OutcomePublisher.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new BetController(betService, Mappers.getMapper(BetMapper.class)), new OutcomeController(outcomePublisher))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final String betJson = """
            {"betId":"bet","userId":"user-1","eventId":"event-1","eventMarketId":"match-winner",
             "eventWinnerId":"a","betAmount":10.00}
            """;

    @Test
    void placesAndRetrievesBetAndReturnsNotFound() throws Exception {
        var bet = new Bet(BetFixtures.request("bet", "a"));
        when(betService.place(any())).thenReturn(bet);
        when(betService.get("bet")).thenReturn(bet);
        mvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content(betJson))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/bets/bet")).andExpect(status().isOk()).andExpect(jsonPath("$.betId").value("bet"));
        when(betService.get("absent")).thenThrow(new NoSuchElementException("Bet not found"));
        mvc.perform(get("/api/bets/absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Bet not found"));
        when(betService.place(any())).thenThrow(new IllegalStateException("Bet ID already exists"));
        mvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content(betJson)).andExpect(status().isConflict());
    }

    @Test
    void validatesRequestBodiesBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content(betJson.replace("10.00", "-1")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bets").contentType(MediaType.APPLICATION_JSON).content(betJson.replace("10.00", "0.001")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/event-outcomes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/event-outcomes").contentType(MediaType.APPLICATION_JSON).content("bad"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(betService, outcomePublisher);
    }

    @Test
    void acceptedRequiresBrokerAcknowledgement() throws Exception {
        String body = """
                {"eventId":"event-1","eventName":"Final","eventWinnerId":"a"}
                """;
        mvc.perform(post("/api/event-outcomes").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        verify(outcomePublisher).publish(new EventOutcome("event-1", "Final", "a"));
        doThrow(new BrokerUnavailableException(new IllegalStateException("offline"))).when(outcomePublisher).publish(any());
        mvc.perform(post("/api/event-outcomes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").exists());
    }
}
