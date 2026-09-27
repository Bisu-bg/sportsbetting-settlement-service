package com.sportsbetting.settlement.domain.outcome.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** A coordination row for admission and matching of one event. */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventLock {
    @Id @NotBlank @Size(max = 100) @Column(length = 100) private String eventId;
}
