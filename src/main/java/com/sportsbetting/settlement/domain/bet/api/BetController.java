package com.sportsbetting.settlement.domain.bet.api;

import com.sportsbetting.settlement.domain.bet.service.BetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bets")
@RequiredArgsConstructor
public class BetController {
    private final BetService betService;
    private final BetMapper betMapper;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BetView place(@Valid @RequestBody PlaceBet request) {
        return betMapper.toView(betService.place(request));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public BetView get(@PathVariable String id) {
        return betMapper.toView(betService.get(id));
    }
}
