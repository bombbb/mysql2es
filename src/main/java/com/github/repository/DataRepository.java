package com.github.repository;

import com.github.model.Relation;
import com.github.util.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

@Component
public class DataRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EsRepository esRepository;
    public DataRepository(JdbcTemplate jdbcTemplate, EsRepository esRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.esRepository = esRepository;
    }

    /** generate scheme of es on the database table structure */
    public Map<String, Map> dbToEsScheme(Relation relation) {
        List<Map<String, Object>> mapList = jdbcTemplate.queryForList(relation.descSql());
        if (A.isNotEmpty(mapList)) {
            boolean scheme = relation.isScheme();
            List<String> keyList = Lists.newArrayList();
            Map<String, Map> propertyMap = Maps.newHashMap();
            Map<String, Boolean> fieldMap = Maps.newHashMap();
            for (Map<String, Object> map : mapList) {
                Object column = map.get("Field");
                Object type = map.get("Type");

                if (U.isNotBlank(column) && U.isNotBlank(type)) {
                    String field = column.toString();

                    Object key = map.get("Key");
                    if (U.isNotBlank(key) && "PRI".equals(key)) {
                        keyList.add(field);
                    }

                    fieldMap.put(field, true);

                    if (scheme) {
                        propertyMap.put(relation.useField(field), Searchs.dbToEsType(type.toString()));
                    }
                }
            }

            List<String> keyColumn = relation.getKeyColumn();
            String table = relation.getTable();
            if (A.isEmpty(keyColumn)) {
                if (A.isEmpty(keyList)) {
                    U.assertException(String.format("table (%s) no primary key, can't create index in es!", table));
                }
                if (keyList.size() > 1) {
                    if (Logs.ROOT_LOG.isWarnEnabled()) {
                        Logs.ROOT_LOG.warn("table ({}) has multi primary key({})", table, A.toStr(keyList));
                    }
                }
                relation.setKeyColumn(keyList);
            } else {
                for (String key : keyColumn) {
                    U.assertNil(fieldMap.get(key), String.format("table (%s) don't have column (%s)", table, key));
                }
            }
            return propertyMap;
        }
        return Collections.emptyMap();
    }

    /** async data to es */
    @Async
    public Future<Boolean> asyncData(Relation relation) {
        if (A.isEmpty(relation.getKeyColumn())) {
            dbToEsScheme(relation);
        }
        saveData(relation);
        return new AsyncResult<>(true);
    }
    private void saveData(Relation relation) {
        String table = relation.getTable();
        String index = relation.useIndex();
        String type = relation.getType();

        String lastValue = F.read(table, index, type);
        String countSql = relation.countSql(lastValue);
        long start = System.currentTimeMillis();
        Integer count = A.first(jdbcTemplate.queryForList(countSql, Integer.class));
        if (Logs.ROOT_LOG.isInfoEnabled()) {
            Logs.ROOT_LOG.info("count sql({}) time({}) return({})",
                    countSql, (System.currentTimeMillis() - start + "ms"), count);
        }
        if (U.less0(count)) {
            return;
        }

        for (;;) {
            lastValue = handleGreaterAndEquals(relation, lastValue);
            if (U.isBlank(lastValue)) {
                return;
            }
        }
    }

    private String handleGreaterAndEquals(Relation relation, String lastValue) {
        String sql = relation.querySql(lastValue);
        long sqlStart = System.currentTimeMillis();
        List<Map<String, Object>> dataList = jdbcTemplate.queryForList(sql);
        if (A.isEmpty(dataList)) {
            // if not data, can break loop
            return null;
        }
        String sqlTime = (System.currentTimeMillis() - sqlStart + "ms");

        String index = relation.useIndex();
        String type = relation.getType();

        long esStart = System.currentTimeMillis();
        int size = esRepository.saveDataToEs(index, type, fixDocument(relation, dataList));
        if (size == 0) {
            // if write to es false, can break loop
            return null;
        }
        String esTime = (System.currentTimeMillis() - esStart + "ms");
        if (Logs.ROOT_LOG.isInfoEnabled()) {
            Logs.ROOT_LOG.info("sql({}) time({}) return size({}), batch to({}) time({}) success({})",
                    sql, sqlTime, dataList.size(), (index + "/" + type), esTime, size);
        }

        lastValue = getLast(relation, dataList);
        if (U.isBlank(lastValue)) {
            // if last data was nil, can break loop
            return null;
        }

        handleEquals(relation, lastValue);
        // write last record in temp file
        F.write(relation.getTable(), index, type, lastValue);

        // if sql: limit 1000, query data size 900, can break loop
        if (dataList.size() < relation.getLimit()) {
            return null;
        }
        return lastValue;
    }
    private void handleEquals(Relation relation, String tempColumnValue) {
        // if was number: id > 123, don't need to id = 123
        // not number: time > '2010-10-10 00:00:01', this: time = '2010-10-10 00:00:01'
        if (U.isNotNumber(tempColumnValue)) {
            String equalsCountSql = relation.equalsCountSql(tempColumnValue);
            long start = System.currentTimeMillis();
            Integer equalsCount = A.first(jdbcTemplate.queryForList(equalsCountSql, Integer.class));
            if (Logs.ROOT_LOG.isInfoEnabled()) {
                Logs.ROOT_LOG.info("equals count sql({}) time({}) return({})",
                        equalsCountSql, (System.currentTimeMillis() - start + "ms"), equalsCount);
            }

            if (U.greater0(equalsCount)) {
                int equalsLoopCount = relation.loopCount(equalsCount);
                for (int i = 0; i < equalsLoopCount; i++) {
                    String equalsSql = relation.equalsQuerySql(tempColumnValue, i);
                    long sqlStart = System.currentTimeMillis();
                    List<Map<String, Object>> equalsDataList = jdbcTemplate.queryForList(equalsSql);
                    if (A.isEmpty(equalsDataList)) {
                        // if not data, can break equals handle
                        return;
                    }
                    String sqlTime = (System.currentTimeMillis() - sqlStart + "ms");

                    String index = relation.useIndex();
                    String type = relation.getType();
                    long esStart = System.currentTimeMillis();
                    int size = esRepository.saveDataToEs(index, type, fixDocument(relation, equalsDataList));
                    if (size == 0) {
                        // if success was 0, can break equals handle
                        return;
                    }
                    String esTime = (System.currentTimeMillis() - esStart + "ms");
                    if (Logs.ROOT_LOG.isInfoEnabled()) {
                        Logs.ROOT_LOG.info("equals sql({}) time({}) return size({}), batch to({}) time({}) success({})",
                                equalsSql, sqlTime, equalsDataList.size(), (index + "/" + type), esTime, size);
                    }

                    // if sql: limit 1000, 1000, query data size 900, can break equals handle
                    if (equalsDataList.size() < relation.getLimit()) {
                        return;
                    }
                }
            }
        }
    }

    /** write last record in temp file */
    private String getLast(Relation relation, List<Map<String, Object>> dataList) {
        Map<String, Object> last = A.last(dataList);
        if (A.isNotEmpty(last)) {
            Object obj = last.get(relation.getIncrementColumnAlias());
            if (U.isNotBlank(obj)) {
                // if was Date return 'yyyy-MM-dd HH:mm:ss', else return toStr
                String lastData;
                if (obj instanceof Date) {
                    // lastData = String.valueOf(((Date) obj).getTime());
                    lastData = Dates.format((Date) obj, Dates.Type.YYYY_MM_DD_HH_MM_SS);
                } else {
                    lastData = obj.toString();
                }
                return lastData;
            }
        }
        return null;
    }
    /** traverse the Database Result and organize into es Document */
    private Map<String, String> fixDocument(Relation relation, List<Map<String, Object>> dataList) {
        Map<String, String> documents = Maps.newHashMap();
        for (Map<String, Object> obj : dataList) {
            StringBuilder sbd = new StringBuilder();
            String idPrefix = relation.getIdPrefix();
            if (U.isNotBlank(idPrefix)) {
                sbd.append(idPrefix);
            }
            for (String primary : relation.getKeyColumn()) {
                sbd.append(obj.get(primary)).append("-");
            }
            if (sbd.toString().endsWith("-")) {
                sbd.delete(sbd.length() - 1, sbd.length());
            }
            String idSuffix = relation.getIdSuffix();
            if (U.isNotBlank(idSuffix)) {
                sbd.append(idSuffix);
            }
            String id = sbd.toString();
            // Document no id, can't be save
            if (U.isNotBlank(id)) {
                Map<String, Object> dataMap = Maps.newHashMap();
                for (Map.Entry<String, Object> entry : obj.entrySet()) {
                    String key = relation.useField(entry.getKey());
                    if (U.isNotBlank(key)) {
                        Object value = entry.getValue();
                        // field has suggest and null, can't be write => https://elasticsearch.cn/question/4051
                        dataMap.put(key, U.isBlank(value) ? " " : value);
                        // dataMap.put(key, value);
                    }
                }
                // Document no data, don't need to save? or update to nil?
                if (A.isNotEmpty(dataMap)) {
                    documents.put(id, Jsons.toJson(dataMap));
                }
            }
        }
        return documents;
    }
}
