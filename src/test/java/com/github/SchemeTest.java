package com.github;

import com.github.model.Config;
import com.github.model.Relation;
import com.github.repository.DataRepository;
import com.github.repository.EsRepository;
import com.github.util.A;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

@SpringBootTest
@RunWith(SpringRunner.class)
public class SchemeTest {

    private Config config;
    private EsRepository esRepository;
    private DataRepository dataRepository;
    public SchemeTest(Config config, EsRepository esRepository, DataRepository dataRepository) {
        this.config = config;
        this.esRepository = esRepository;
        this.dataRepository = dataRepository;
    }

    @Sql({"classpath:sql/scheme.sql"})
    @Sql(value = {"classpath:sql/delete.sql"}, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    @Test
    public void test() {
        for (Relation relation : config.getRelation()) {
            String index = relation.useIndex();
            String type = relation.getType();

            Map<String, Map> properties = dataRepository.dbToEsScheme(relation);
            if (relation.isScheme() && A.isNotEmpty(properties)) {
                esRepository.saveScheme(index, type, properties);
            }

            esRepository.deleteScheme(index, type);
        }
    }
}
