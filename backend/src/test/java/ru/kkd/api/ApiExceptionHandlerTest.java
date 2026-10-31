package ru.kkd.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.kkd.application.IssueService;
import ru.kkd.application.RunQueryService;
import ru.kkd.application.SummaryService;

class ApiExceptionHandlerTest {

    private final RunQueryService runs = mock(RunQueryService.class);
    private final SummaryService summaries = mock(SummaryService.class);
    private final IssueService issues = mock(IssueService.class);
    private MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc =
                MockMvcBuilders.standaloneSetup(new KkdController(runs, summaries, issues))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unexpectedFailuresDoNotLeakSqlCredentialsOrImplementationDetails(boolean databaseFailure)
            throws Exception {
        String sensitiveDetails =
                "SELECT secret FROM kkd.kkd_run; jdbc:postgresql://internal password=secret";
        RuntimeException failure =
                databaseFailure
                        ? new DataAccessResourceFailureException(sensitiveDetails)
                        : new IllegalArgumentException(sensitiveDetails);
        when(runs.listRuns()).thenThrow(failure);
        var response =
                mvc.perform(get("/api/runs"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                        .andExpect(jsonPath("$.error.message").value("Internal server error"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getContentAsString())
                .doesNotContain(
                        "SELECT", "secret", "jdbc", "password", failure.getClass().getSimpleName());
    }

    @Test
    void invalidPaginationFailsBeforeCallingApplicationServices() throws Exception {
        mvc.perform(get("/api/kkd/issues").param("run_id", "run").param("limit", "201"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(runs, summaries, issues);
    }
}
