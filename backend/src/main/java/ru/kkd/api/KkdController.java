package ru.kkd.api;

import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.kkd.application.IssueService;
import ru.kkd.application.RunQueryService;
import ru.kkd.application.SummaryService;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class KkdController {

    private final RunQueryService runs;
    private final SummaryService summaries;
    private final IssueService issues;

    public KkdController(RunQueryService runs, SummaryService summaries, IssueService issues) {
        this.runs = runs;
        this.summaries = summaries;
        this.issues = issues;
    }

    @GetMapping("/runs")
    public List<ApiResponses.Run> runs(@RequestParam MultiValueMap<String, String> parameters) {
        new RequestParameters(parameters, Set.of());
        return runs.listRuns().stream().map(ApiMapper::run).toList();
    }

    @GetMapping("/kkd/summary")
    public ApiResponses.Summary summary(@RequestParam MultiValueMap<String, String> parameters) {
        var parsed = new RequestParameters(parameters, Set.of("run_id", "unit_id"));
        return ApiMapper.summary(
                summaries.summary(parsed.required("run_id"), parsed.required("unit_id")));
    }

    @GetMapping("/kkd/issues")
    public ApiResponses.Issues issues(@RequestParam MultiValueMap<String, String> parameters) {
        var parsed =
                new RequestParameters(
                        parameters,
                        Set.of("run_id", "unit_id", "rule", "severity", "limit", "offset"));
        return ApiMapper.issues(issues.page(parsed.issueFilter()));
    }
}
