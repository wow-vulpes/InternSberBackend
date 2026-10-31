package ru.kkd.domain.model;

import java.util.List;

public record IssuePage(List<IssueDetails> items, long total, int limit, int offset) {

    public IssuePage {
        items = List.copyOf(items);
    }
}
