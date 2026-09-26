package com.fakturacnysoftver.web.activity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Aktivita (akcia) s prehladom rozpoctu, obsadenosti a uloh. */
public record Activity(
        long id,
        String code,
        String name,
        String kind,
        String status,
        LocalDate startsOn,
        LocalDate endsOn,
        String location,
        String description,
        BigDecimal budget,
        BigDecimal spent,
        BigDecimal income,
        int seatsNeeded,
        int seatsFilled,
        int tasksTotal,
        int tasksDone,
        int tasksOverdue) {

    public String kindLabel() {
        return ActivityKind.parse(this.kind).label();
    }

    public String statusLabel() {
        return switch (this.status) {
            case "PREBIEHA" -> "Prebieha";
            case "UKONCENA" -> "Ukončená";
            case "ZRUSENA" -> "Zrušená";
            default -> "Príprava";
        };
    }

    public int staffingPercent() {
        return this.seatsNeeded == 0 ? 0 : Math.min(100, this.seatsFilled * 100 / this.seatsNeeded);
    }

    public int tasksPercent() {
        return this.tasksTotal == 0 ? 0 : this.tasksDone * 100 / this.tasksTotal;
    }
}
