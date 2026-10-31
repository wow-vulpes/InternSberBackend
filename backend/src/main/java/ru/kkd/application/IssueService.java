package ru.kkd.application;

import org.springframework.stereotype.Service;
import ru.kkd.domain.model.IssueFilter;
import ru.kkd.domain.model.IssuePage;
import ru.kkd.persistence.IssueRepository;
import ru.kkd.persistence.SummaryRepository;

@Service
public class IssueService {

    private final RunQueryService runs;
    private final SummaryRepository snapshots;
    private final IssueRepository issues;

    public IssueService(RunQueryService runs, SummaryRepository snapshots, IssueRepository issues) {
        this.runs = runs;
        this.snapshots = snapshots;
        this.issues = issues;
    }

    public IssuePage page(IssueFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Issue filter must not be null");
        }
        runs.completedRun(filter.runId());
        if (filter.unitId() != null
                && snapshots.findUnit(filter.runId(), filter.unitId()).isEmpty()) {
            throw new ResourceNotFoundException("Unit", filter.unitId());
        }
        return issues.page(filter);
    }
}
