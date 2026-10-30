package ru.kkd.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DemoDataRepository {

    private final JdbcTemplate jdbc;

    public DemoDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockSources() {
        jdbc.execute("LOCK TABLE kkd.unit, kkd.goal, kkd.key_result IN SHARE ROW EXCLUSIVE MODE");
    }

    public boolean isEmpty() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT NOT EXISTS (SELECT 1 FROM kkd.unit)
                            AND NOT EXISTS (SELECT 1 FROM kkd.goal)
                            AND NOT EXISTS (SELECT 1 FROM kkd.key_result)
                            AND NOT EXISTS (SELECT 1 FROM kkd.kkd_run)
                        """,
                        Boolean.class));
    }

    public void seedFirstState(LocalDate date) {
        jdbc.update("INSERT INTO kkd.unit(id,name) VALUES ('U1','Банк')");
        jdbc.update(
                "INSERT INTO kkd.unit(id,name,parent_id) VALUES ('U2','Разработка','U1'),('U3','Сопровождение','U2')");
        for (int number = 1; number <= 24; number++) {
            String goalId = "G%02d".formatted(number);
            String owner =
                    switch (number) {
                        case 1, 5, 24 -> null;
                        case 9 -> "   ";
                        default -> "employee-%02d".formatted(number);
                    };
            jdbc.update(
                    "INSERT INTO kkd.goal(id,title,unit_id,owner_id,parent_goal_id,status,deadline) VALUES (?,?,?,?,?,?,?)",
                    goalId,
                    "Демонстрационная цель %02d".formatted(number),
                    "U" + ((number - 1) / 8 + 1),
                    owner,
                    number == 1 ? null : "G01",
                    number == 24 ? "INACTIVE" : "ACTIVE",
                    date.plusMonths(2));
            if (number == 2 || number == 10) {
                continue;
            }
            BigDecimal weight =
                    new BigDecimal(number == 3 || number == 9 || number == 24 ? "90" : "100");
            LocalDate updatedOn =
                    switch (number) {
                        case 4, 9, 24 -> null;
                        case 8 -> date.minusDays(46);
                        case 11 -> date.minusDays(45);
                        case 12 -> date.minusDays(38);
                        default -> date;
                    };
            insertKeyResult(number, weight, updatedOn);
        }
    }

    public void applySecondState(LocalDate date) {
        jdbc.update("UPDATE kkd.goal SET owner_id='employee-01' WHERE id='G01'");
        insertKeyResult(2, new BigDecimal("100"), date);
        jdbc.update("UPDATE kkd.goal SET owner_id='' WHERE id='G06'");
        jdbc.update("UPDATE kkd.key_result SET weight=80 WHERE id='KR07'");
        jdbc.update("UPDATE kkd.key_result SET progress_updated_on=? WHERE id='KR08'", date);
        jdbc.update("UPDATE kkd.goal SET owner_id='employee-09' WHERE id='G09'");
        jdbc.update("UPDATE kkd.key_result SET weight=100 WHERE id='KR09'");
    }

    private void insertKeyResult(int number, BigDecimal weight, LocalDate updatedOn) {
        jdbc.update(
                "INSERT INTO kkd.key_result(id,title,goal_id,target_value,actual_value,weight,progress_updated_on) VALUES (?,?,?,?,?,?,?)",
                "KR%02d".formatted(number),
                "Ключевой результат %02d".formatted(number),
                "G%02d".formatted(number),
                new BigDecimal("100"),
                new BigDecimal("50"),
                weight,
                updatedOn);
    }
}
