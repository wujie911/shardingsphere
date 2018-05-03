/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.dbtest.asserts;

import io.shardingjdbc.core.api.MasterSlaveDataSourceFactory;
import io.shardingjdbc.core.api.ShardingDataSourceFactory;
import io.shardingjdbc.core.constant.DatabaseType;
import io.shardingjdbc.core.jdbc.core.datasource.ShardingDataSource;
import io.shardingjdbc.dbtest.IntegrateTestEnvironment;
import io.shardingjdbc.dbtest.common.DatabaseUtil;
import io.shardingjdbc.dbtest.common.PathUtil;
import io.shardingjdbc.dbtest.config.AnalyzeDataset;
import io.shardingjdbc.dbtest.config.bean.AssertDDLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDMLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertDQLDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertSubDefinition;
import io.shardingjdbc.dbtest.config.bean.AssertsDefinition;
import io.shardingjdbc.dbtest.config.bean.ColumnDefinition;
import io.shardingjdbc.dbtest.config.bean.DatasetDatabase;
import io.shardingjdbc.dbtest.config.bean.DatasetDefinition;
import io.shardingjdbc.dbtest.config.bean.ParameterDefinition;
import io.shardingjdbc.dbtest.exception.DbTestException;
import io.shardingjdbc.dbtest.init.InItCreateSchema;
import io.shardingjdbc.test.sql.SQLCasesLoader;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.xml.sax.SAXException;

import javax.sql.DataSource;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AssertEngine {
    
    public static final Map<String, AssertsDefinition> ASSERTDEFINITIONMAPS = new HashMap<>();
    
    public static final List<String> DEFAULT_DATABASES = Arrays.asList("db", "dbtbl", "nullable");
    
    /**
     * add check use cases.
     *
     * @param assertPath        Check the use case storage path
     * @param assertsDefinition Check use case definitions
     */
    public static void addAssertDefinition(final String assertPath, final AssertsDefinition assertsDefinition) {
        ASSERTDEFINITIONMAPS.put(assertPath, assertsDefinition);
    }
    
    /**
     * Execution use case.
     *
     * @param path Check the use case storage path
     * @param id   Unique primary key for a use case
     * @return Successful implementation
     */
    public static boolean runAssert(final String path, final String id) {
        
        AssertsDefinition assertsDefinition = ASSERTDEFINITIONMAPS.get(path);
        
        String rootPath = path.substring(0, path.lastIndexOf(File.separator) + 1);
        assertsDefinition.setPath(rootPath);
        
        try {
            String msg = "The file path " + path + ", under which id is " + id;
            
            List<String> dbNames = new ArrayList();
            if (StringUtils.isNotBlank(assertsDefinition.getBaseConfig())) {
                String[] dbs = StringUtils.split(assertsDefinition.getBaseConfig(), ",");
                for (String each : dbs) {
                    dbNames.add(each);
                }
            } else {
                dbNames.addAll(AssertEngine.DEFAULT_DATABASES);
            }
            
            for (String each : dbNames) {
                String initDataFile = PathUtil.getPath(assertsDefinition.getInitDataFile(), rootPath);
                String initDataPath = initDataFile + "/" + each;
                File fileDirDatabase = new File(initDataPath);
                if (fileDirDatabase.exists()) {
                    File[] fileDatabases = fileDirDatabase.listFiles();
                    List<String> dbs = new ArrayList<>(fileDatabases.length);
                    if (fileDatabases != null) {
                        for (File fileDatabase : fileDatabases) {
                            String databaseName = fileDatabase.getName();
                            databaseName = databaseName.substring(0, databaseName.indexOf("."));
                            dbs.add(databaseName);
                        }
                    }
                    
                    onlyDatabaseRun(each, path, id, assertsDefinition, rootPath, msg, initDataPath, dbs);
                }
            }
            
            
        } catch (ParseException | XPathExpressionException | SQLException | ParserConfigurationException | SAXException | IOException e) {
            throw new DbTestException(e);
        }
        return true;
    }
    
    private static void onlyDatabaseRun(final String dbName, final String path, final String id, final AssertsDefinition assertsDefinition, final String rootPath, final String msg, final String initDataPath, final List<String> dbs) throws IOException, SQLException, SAXException, ParserConfigurationException, XPathExpressionException, ParseException {
        DataSource dataSource = null;
        try {
            for (DatabaseType each : IntegrateTestEnvironment.getInstance().getDatabaseTypes()) {
                Map<String, DataSource> dataSourceMaps = new HashMap<>();
                
                for (String db : dbs) {
                    DataSource subDataSource = InItCreateSchema.buildDataSource(db, each);
                    dataSourceMaps.put(db, subDataSource);
                }
                
                if ("true".equals(assertsDefinition.getMasterslave())) {
                    String configPath = PathUtil.getPath(assertsDefinition.getShardingRuleConfig(), rootPath) + "-" + dbName + ".yaml";
                    dataSource = getMasterSlaveDataSource(dataSourceMaps, configPath);
                } else {
                    String configPath = PathUtil.getPath(assertsDefinition.getShardingRuleConfig(), rootPath) + "-" + dbName + ".yaml";
                    dataSource = getDataSource(dataSourceMaps, configPath);
                }
                dqlRun(each, initDataPath, dbName, path, id, assertsDefinition, rootPath, msg, dataSource, dataSourceMaps, dbs);
                dmlRun(each, initDataPath, dbName, path, id, assertsDefinition, rootPath, msg, dataSource, dataSourceMaps, dbs);
                ddlRun(each, id, dbName, assertsDefinition, rootPath, msg, dataSource);
            }
        } finally {
            if (dataSource != null) {
                if (dataSource instanceof ShardingDataSource) {
                    ((ShardingDataSource) dataSource).close();
                }
            }
        }
    }
    
    private static void ddlRun(final DatabaseType databaseType, final String id, final String dbName, final AssertsDefinition assertsDefinition, final String rootPath, final String msg, final DataSource dataSource) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (AssertDDLDefinition each : assertsDefinition.getAssertDDL()) {
            if (id.equals(each.getId())) {
                List<DatabaseType> databaseTypes = InItCreateSchema.getDatabaseTypes(each.getDatabaseConfig());
                if (!databaseTypes.contains(databaseType)) {
                    break;
                }
                AssertDDLDefinition anAssert = each;
                String baseConfig = anAssert.getBaseConfig();
                if (StringUtils.isNotBlank(baseConfig)) {
                    String[] baseConfigs = StringUtils.split(baseConfig, ",");
                    boolean flag = true;
                    for (String config : baseConfigs) {
                        if (dbName.equals(config)) {
                            flag = false;
                        }
                    }
                    //Skip use cases that do not need to run
                    if (flag) {
                        continue;
                    }
                }
                String rootsql = anAssert.getSql();
                rootsql = SQLCasesLoader.getInstance().getSupportedSQL(rootsql);
                String expectedDataFile = PathUtil.getPath("asserts/ddl/" + dbName + "/" + anAssert.getExpectedDataFile(), rootPath);
                if (!new File(expectedDataFile).exists()) {
                    expectedDataFile = PathUtil.getPath("asserts/ddl/" + anAssert.getExpectedDataFile(), rootPath);
                }
                if (anAssert.getParameter().getValues().isEmpty() && anAssert.getParameter().getValueReplaces().isEmpty()) {
                    List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                    if (subAsserts.isEmpty()) {
                        doUpdateUseStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doUpdateUseStatementToExecuteDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doUpdateUsePreparedStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doUpdateUsePreparedStatementToExecuteDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                    } else {
                        ddlSubRun(databaseType, dbName, rootPath, msg, dataSource, anAssert, rootsql, expectedDataFile, subAsserts);
                    }
                } else {
                    doUpdateUseStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                    doUpdateUseStatementToExecuteDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                    doUpdateUsePreparedStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                    doUpdateUsePreparedStatementToExecuteDDL(databaseType, dbName, expectedDataFile, dataSource, anAssert, rootsql, msg);
                    List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                    if (!subAsserts.isEmpty()) {
                        ddlSubRun(databaseType, dbName, rootPath, msg, dataSource, anAssert, rootsql, expectedDataFile, subAsserts);
                    }
                }
                
                break;
            }
        }
    }
    
    private static void ddlSubRun(final DatabaseType databaseType, final String dbName, final String rootPath, final String msg, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (AssertSubDefinition subAssert : subAsserts) {
            List<DatabaseType> databaseSubTypes = InItCreateSchema.getDatabaseTypes(subAssert.getDatabaseConfig());
            
            if (!databaseSubTypes.contains(databaseType)) {
                break;
            }
            String baseConfig = subAssert.getBaseConfig();
            if (StringUtils.isNotBlank(baseConfig)) {
                String[] baseConfigs = StringUtils.split(baseConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (dbName.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            
            String expectedDataFileSub = subAssert.getExpectedDataFile();
            ParameterDefinition parameter = subAssert.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = PathUtil.getPath("asserts/ddl/" + dbName + "/" + expectedDataFileSub, rootPath);
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = PathUtil.getPath("asserts/ddl/" + expectedDataFileSub, rootPath);
                }
            }
            if (parameter == null) {
                parameter = anAssert.getParameter();
            }
            AssertDDLDefinition anAssertSub = new AssertDDLDefinition(anAssert.getId(), anAssert.getInitSql(),
                    anAssert.getBaseConfig(), anAssert.getCleanSql(), expectedDataFileSub,
                    anAssert.getDatabaseConfig(), anAssert.getSql(), anAssert.getTable(),
                    parameter, anAssert.getSubAsserts());
            doUpdateUseStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doUpdateUseStatementToExecuteDDL(databaseType, dbName, expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doUpdateUsePreparedStatementToExecuteUpdateDDL(databaseType, dbName, expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doUpdateUsePreparedStatementToExecuteDDL(databaseType, dbName, expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
        }
    }
    
    private static void dmlRun(final DatabaseType databaseType, final String initDataFile, final String dbName, final String path, final String id, final AssertsDefinition assertsDefinition, final String rootPath, final String msg, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final List<String> dbs) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        for (AssertDMLDefinition each : assertsDefinition.getAssertDML()) {
            if (id.equals(each.getId())) {
                List<DatabaseType> databaseTypes = InItCreateSchema.getDatabaseTypes(each.getDatabaseConfig());
                if (!databaseTypes.contains(databaseType)) {
                    break;
                }
                AssertDMLDefinition anAssert = each;
                String baseConfig = anAssert.getBaseConfig();
                if (StringUtils.isNotBlank(baseConfig)) {
                    String[] baseConfigs = StringUtils.split(baseConfig, ",");
                    boolean flag = true;
                    for (String config : baseConfigs) {
                        if (dbName.equals(config)) {
                            flag = false;
                        }
                    }
                    //Skip use cases that do not need to run
                    if (flag) {
                        continue;
                    }
                }
                String rootsql = anAssert.getSql();
                rootsql = SQLCasesLoader.getInstance().getSupportedSQL(rootsql);
                Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
                Map<String, String> sqls = new HashMap<>();
                getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
                
                if (mapDatasetDefinition.isEmpty()) {
                    throw new DbTestException(path + "  Use cases cannot be parsed");
                }
                
                if (sqls.isEmpty()) {
                    throw new DbTestException(path + "  The use case cannot initialize the data");
                }
                String expectedDataFile = PathUtil.getPath("asserts/dml/" + dbName + "/" + anAssert.getExpectedDataFile(), rootPath);
                if (!new File(expectedDataFile).exists()) {
                    expectedDataFile = PathUtil.getPath("asserts/dml/" + anAssert.getExpectedDataFile(), rootPath);
                }
                
                int resultDoUpdateUseStatementToExecuteUpdate = 0;
                int resultDoUpdateUseStatementToExecute = 0;
                int resultDoUpdateUsePreparedStatementToExecuteUpdate = 0;
                int resultDoUpdateUsePreparedStatementToExecute = 0;
                if (anAssert.getParameter().getValues().isEmpty() && anAssert.getParameter().getValueReplaces().isEmpty()) {
                    List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                    if (subAsserts.isEmpty()) {
                        resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                        resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                        resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                        resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                    } else {
                        for (AssertSubDefinition subAssert : subAsserts) {
                            List<DatabaseType> databaseSubTypes = InItCreateSchema.getDatabaseTypes(subAssert.getDatabaseConfig());
                            if (!databaseSubTypes.contains(databaseType)) {
                                break;
                            }
                            String baseConfigSub = subAssert.getBaseConfig();
                            if (StringUtils.isNotBlank(baseConfigSub)) {
                                String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                                boolean flag = true;
                                for (String config : baseConfigs) {
                                    if (dbName.equals(config)) {
                                        flag = false;
                                    }
                                }
                                //Skip use cases that do not need to run
                                if (flag) {
                                    continue;
                                }
                            }
                            
                            String expectedDataFileSub = subAssert.getExpectedDataFile();
                            ParameterDefinition parameter = subAssert.getParameter();
                            ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                            String expectedDataFileTmp = expectedDataFile;
                            if (StringUtils.isBlank(expectedDataFileSub)) {
                                expectedDataFileSub = anAssert.getExpectedDataFile();
                            } else {
                                expectedDataFileTmp = PathUtil.getPath("asserts/dml/" + dbName + "/" + expectedDataFileSub, rootPath);
                                if (!new File(expectedDataFileTmp).exists()) {
                                    expectedDataFileTmp = PathUtil.getPath("asserts/dml/" + expectedDataFileSub, rootPath);
                                }
                            }
                            if (parameter == null) {
                                parameter = anAssert.getParameter();
                            }
                            if (expectedParameter == null) {
                                expectedParameter = anAssert.getParameter();
                            }
                            AssertDMLDefinition anAssertSub = new AssertDMLDefinition(anAssert.getId(),
                                    expectedDataFileSub, anAssert.getBaseConfig(), subAssert.getExpectedUpdate(), anAssert.getDatabaseConfig(), anAssert.getSql(),
                                    anAssert.getExpectedSql(), parameter, expectedParameter, anAssert.getSubAsserts());
                            resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                        }
                    }
                } else {
                    resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                    resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                    resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                    resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFile, dataSource, dataSourceMaps, anAssert, rootsql, mapDatasetDefinition, sqls, msg);
                    List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                    if (!subAsserts.isEmpty()) {
                        for (AssertSubDefinition subAssert : subAsserts) {
                            List<DatabaseType> databaseSubTypes = InItCreateSchema.getDatabaseTypes(subAssert.getDatabaseConfig());
                            if (!databaseSubTypes.contains(databaseType)) {
                                break;
                            }
                            String baseConfigSub = subAssert.getBaseConfig();
                            if (StringUtils.isNotBlank(baseConfigSub)) {
                                String[] baseConfigs = StringUtils.split(baseConfigSub, ",");
                                boolean flag = true;
                                for (String config : baseConfigs) {
                                    if (dbName.equals(config)) {
                                        flag = false;
                                    }
                                }
                                //Skip use cases that do not need to run
                                if (flag) {
                                    continue;
                                }
                            }
                            
                            String expectedDataFileSub = subAssert.getExpectedDataFile();
                            ParameterDefinition parameter = subAssert.getParameter();
                            ParameterDefinition expectedParameter = subAssert.getExpectedParameter();
                            String expectedDataFileTmp = expectedDataFile;
                            if (StringUtils.isBlank(expectedDataFileSub)) {
                                expectedDataFileSub = anAssert.getExpectedDataFile();
                            } else {
                                expectedDataFileTmp = PathUtil.getPath("asserts/dml/" + dbName + "/" + expectedDataFileSub, rootPath);
                                if (!new File(expectedDataFileTmp).exists()) {
                                    expectedDataFileTmp = PathUtil.getPath("asserts/dml/" + expectedDataFileSub, rootPath);
                                }
                            }
                            if (parameter == null) {
                                parameter = anAssert.getParameter();
                            }
                            if (expectedParameter == null) {
                                expectedParameter = anAssert.getParameter();
                            }
                            AssertDMLDefinition anAssertSub = new AssertDMLDefinition(anAssert.getId(),
                                    expectedDataFileSub, anAssert.getBaseConfig(), subAssert.getExpectedUpdate(), anAssert.getDatabaseConfig(), anAssert.getSql(),
                                    anAssert.getExpectedSql(), parameter, expectedParameter, anAssert.getSubAsserts());
                            resultDoUpdateUseStatementToExecuteUpdate = resultDoUpdateUseStatementToExecuteUpdate + doUpdateUseStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUseStatementToExecute = resultDoUpdateUseStatementToExecute + doUpdateUseStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUsePreparedStatementToExecuteUpdate = resultDoUpdateUsePreparedStatementToExecuteUpdate + doUpdateUsePreparedStatementToExecuteUpdate(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                            resultDoUpdateUsePreparedStatementToExecute = resultDoUpdateUsePreparedStatementToExecute + doUpdateUsePreparedStatementToExecute(expectedDataFileTmp, dataSource, dataSourceMaps, anAssertSub, rootsql, mapDatasetDefinition, sqls, msg);
                        }
                    }
                }
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error UpdateUseStatementToExecuteUpdate" + msg, anAssert.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecuteUpdate);
                    Assert.assertEquals("Update row number error UpdateUseStatementToExecute" + msg, anAssert.getExpectedUpdate().intValue(), resultDoUpdateUseStatementToExecute);
                    Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecuteUpdate" + msg, anAssert.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecuteUpdate);
                    Assert.assertEquals("Update row number error UpdateUsePreparedStatementToExecute" + msg, anAssert.getExpectedUpdate().intValue(), resultDoUpdateUsePreparedStatementToExecute);
                }
                break;
            }
        }
    }
    
    
    private static void dqlRun(final DatabaseType databaseType, final String initDataFile, final String dbName, final String path, final String id, final AssertsDefinition assertsDefinition, final String rootPath, final String msg, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final List<String> dbs) throws IOException, SAXException, ParserConfigurationException, XPathExpressionException, SQLException, ParseException {
        for (AssertDQLDefinition each : assertsDefinition.getAssertDQL()) {
            
            if (id.equals(each.getId())) {
                List<DatabaseType> databaseTypes = InItCreateSchema.getDatabaseTypes(each.getDatabaseConfig());
                if (!databaseTypes.contains(databaseType)) {
                    break;
                }
                
                AssertDQLDefinition anAssert = each;
                String baseConfig = anAssert.getBaseConfig();
                if (StringUtils.isNotBlank(baseConfig)) {
                    String[] baseConfigs = StringUtils.split(baseConfig, ",");
                    boolean flag = true;
                    for (String config : baseConfigs) {
                        if (dbName.equals(config)) {
                            flag = false;
                        }
                    }
                    //Skip use cases that do not need to run
                    if (flag) {
                        continue;
                    }
                }
                String rootsql = anAssert.getSql();
                rootsql = SQLCasesLoader.getInstance().getSupportedSQL(rootsql);
                Map<String, DatasetDefinition> mapDatasetDefinition = new HashMap<>();
                Map<String, String> sqls = new HashMap<>();
                getInitDatas(dbs, initDataFile, mapDatasetDefinition, sqls);
                
                if (mapDatasetDefinition.isEmpty()) {
                    throw new DbTestException(path + "  Use cases cannot be parsed");
                }
                
                if (sqls.isEmpty()) {
                    throw new DbTestException(path + "  The use case cannot initialize the data");
                }
                
                try {
                    initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
                    
                    String expectedDataFile = PathUtil.getPath("asserts/dql/" + dbName + "/" + anAssert.getExpectedDataFile(), rootPath);
                    if (!new File(expectedDataFile).exists()) {
                        expectedDataFile = PathUtil.getPath("asserts/dql/" + anAssert.getExpectedDataFile(), rootPath);
                    }
                    
                    if (anAssert.getParameter().getValues().isEmpty() && anAssert.getParameter().getValueReplaces().isEmpty()) {
                        List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                        if (subAsserts.isEmpty()) {
                            doSelectUsePreparedStatement(expectedDataFile, dataSource, anAssert, rootsql, msg);
                            doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, anAssert, rootsql, msg);
                            doSelectUseStatement(expectedDataFile, dataSource, anAssert, rootsql, msg);
                            doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, anAssert, rootsql, msg);
                        } else {
                            dqlSubRun(databaseType, dbName, rootPath, msg, dataSource, anAssert, rootsql, expectedDataFile, subAsserts);
                        }
                        
                    } else {
                        doSelectUsePreparedStatement(expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doSelectUsePreparedStatementToExecuteSelect(expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doSelectUseStatement(expectedDataFile, dataSource, anAssert, rootsql, msg);
                        doSelectUseStatementToExecuteSelect(expectedDataFile, dataSource, anAssert, rootsql, msg);
                        
                        List<AssertSubDefinition> subAsserts = anAssert.getSubAsserts();
                        if (!subAsserts.isEmpty()) {
                            dqlSubRun(databaseType, dbName, rootPath, msg, dataSource, anAssert, rootsql, expectedDataFile, subAsserts);
                        }
                    }
                } finally {
                    clearTableData(dataSourceMaps, mapDatasetDefinition);
                }
                
            }
        }
    }
    
    private static void dqlSubRun(final DatabaseType databaseType, final String dbName, final String rootPath, final String msg, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql, final String expectedDataFile, final List<AssertSubDefinition> subAsserts) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (AssertSubDefinition subAssert : subAsserts) {
            List<DatabaseType> databaseSubTypes = InItCreateSchema.getDatabaseTypes(subAssert.getDatabaseConfig());
            
            if (!databaseSubTypes.contains(databaseType)) {
                break;
            }
            String baseSubConfig = subAssert.getBaseConfig();
            if (StringUtils.isNotBlank(baseSubConfig)) {
                String[] baseConfigs = StringUtils.split(baseSubConfig, ",");
                boolean flag = true;
                for (String config : baseConfigs) {
                    if (dbName.equals(config)) {
                        flag = false;
                    }
                }
                //Skip use cases that do not need to run
                if (flag) {
                    continue;
                }
            }
            
            String expectedDataFileSub = subAssert.getExpectedDataFile();
            ParameterDefinition parameter = subAssert.getParameter();
            String expectedDataFileTmp = expectedDataFile;
            if (StringUtils.isBlank(expectedDataFileSub)) {
                expectedDataFileSub = anAssert.getExpectedDataFile();
            } else {
                expectedDataFileTmp = PathUtil.getPath("asserts/dql/" + dbName + "/" + expectedDataFileSub, rootPath);
                if (!new File(expectedDataFileTmp).exists()) {
                    expectedDataFileTmp = PathUtil.getPath("asserts/dql/" + expectedDataFileSub, rootPath);
                }
            }
            if (parameter == null) {
                parameter = anAssert.getParameter();
            }
            AssertDQLDefinition anAssertSub = new AssertDQLDefinition(anAssert.getId(),
                    anAssert.getBaseConfig(), expectedDataFileSub,
                    anAssert.getDatabaseConfig(), anAssert.getSql(),
                    parameter, anAssert.getSubAsserts());
            
            doSelectUsePreparedStatement(expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doSelectUsePreparedStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doSelectUseStatement(expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
            doSelectUseStatementToExecuteSelect(expectedDataFileTmp, dataSource, anAssertSub, rootsql, msg);
        }
    }
    
    private static int doUpdateUsePreparedStatementToExecute(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteDDL(final DatabaseType databaseType, final String dbName, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
                }
                DatabaseUtil.updateUsePreparedStatementToExecute(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table, msg);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
            }
        }
    }
    
    private static int doUpdateUsePreparedStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
                
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUsePreparedStatementToExecuteUpdateDDL(final DatabaseType databaseType, final String dbName, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
                }
                DatabaseUtil.updateUsePreparedStatementToExecuteUpdate(con, rootsql,
                        anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table, msg);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
            }
        }
    }
    
    private static int doUpdateUseStatementToExecute(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                if (anAssert.getExpectedUpdate() != null) {
                    Assert.assertEquals("Update row number error", anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteDDL(final DatabaseType databaseType, final String dbName, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
                }
                DatabaseUtil.updateUseStatementToExecute(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table, msg);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
            }
        }
    }
    
    private static int doUpdateUseStatementToExecuteUpdate(final String expectedDataFile, final DataSource dataSource, final Map<String, DataSource> dataSourceMaps, final AssertDMLDefinition anAssert, final String rootsql, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            initTableData(dataSourceMaps, sqls, mapDatasetDefinition);
            try (Connection con = dataSource.getConnection();) {
                int actual = DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                if (null != anAssert.getExpectedUpdate()) {
                    Assert.assertEquals("Update row number error" + msg, anAssert.getExpectedUpdate().intValue(), actual);
                }
                String checksql = anAssert.getExpectedSql();
                checksql = SQLCasesLoader.getInstance().getSupportedSQL(checksql);
                DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, checksql,
                        anAssert.getExpectedParameter());
                DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
                return actual;
            }
        } finally {
            clearTableData(dataSourceMaps, mapDatasetDefinition);
        }
    }
    
    private static void doUpdateUseStatementToExecuteUpdateDDL(final DatabaseType databaseType, final String dbName, final String expectedDataFile, final DataSource dataSource, final AssertDDLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try {
            try (Connection con = dataSource.getConnection()) {
                if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                    InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
                }
                if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                    InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
                }
                DatabaseUtil.updateUseStatementToExecuteUpdate(con, rootsql, anAssert.getParameter());
                DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
                
                String table = anAssert.getTable();
                List<ColumnDefinition> columnDefinitions = DatabaseUtil.getColumnDefinitions(con, table);
                DatabaseUtil.assertConfigs(checkDataset, columnDefinitions, table, msg);
            }
        } finally {
            if (StringUtils.isNotBlank(anAssert.getCleanSql())) {
                InItCreateSchema.dropTable(databaseType, anAssert.getCleanSql(), dbName);
            }
            if (StringUtils.isNotBlank(anAssert.getInitSql())) {
                InItCreateSchema.createTable(databaseType, anAssert.getInitSql(), dbName);
            }
        }
    }
    
    private static void doSelectUseStatement(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql, final String msg) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection()) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatement(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            
            DatabaseUtil.assertDatas(checkDataset, ddStatement, msg);
        }
    }
    
    private static void doSelectUseStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql, final String msg) throws SQLException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddStatement = DatabaseUtil.selectUseStatementToExecuteSelect(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddStatement, msg);
        }
    }
    
    private static void doSelectUsePreparedStatement(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection()) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatement(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
        }
    }
    
    private static void doSelectUsePreparedStatementToExecuteSelect(final String expectedDataFile, final DataSource dataSource, final AssertDQLDefinition anAssert, final String rootsql, final String msg) throws SQLException, ParseException, IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        try (Connection con = dataSource.getConnection();) {
            DatasetDatabase ddPreparedStatement = DatabaseUtil.selectUsePreparedStatementToExecuteSelect(con, rootsql, anAssert.getParameter());
            DatasetDefinition checkDataset = AnalyzeDataset.analyze(new File(expectedDataFile), "data");
            DatabaseUtil.assertDatas(checkDataset, ddPreparedStatement, msg);
        }
    }
    
    private static void getInitDatas(final List<String> dbs, final String initDataFile, final Map<String, DatasetDefinition> mapDatasetDefinition, final Map<String, String> sqls)
            throws IOException, SAXException, ParserConfigurationException, XPathExpressionException {
        for (String each : dbs) {
            String tempPath = initDataFile + "/" + each + ".xml";
            File file = new File(tempPath);
            if (file.exists()) {
                DatasetDefinition datasetDefinition = AnalyzeDataset.analyze(file, null);
                mapDatasetDefinition.put(each, datasetDefinition);
                
                Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
                for (Map.Entry<String, List<Map<String, String>>> eachEntry : datas.entrySet()) {
                    String sql = DatabaseUtil.analyzeSql(eachEntry.getKey(), eachEntry.getValue().get(0));
                    sqls.put(eachEntry.getKey(), sql);
                }
            }
        }
    }
    
    private static void clearTableData(final Map<String, DataSource> dataSourceMaps, final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException {
        for (Map.Entry<String, DataSource> eachEntry : dataSourceMaps.entrySet()) {
            DataSource dataSource1 = eachEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachEntry.getKey());
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.cleanAllUsePreparedStatement(conn, eachListEntry.getKey());
                }
            }
        }
    }
    
    private static void initTableData(final Map<String, DataSource> dataSourceMaps, final Map<String, String> sqls, final Map<String, DatasetDefinition> mapDatasetDefinition) throws SQLException, ParseException {
        clearTableData(dataSourceMaps, mapDatasetDefinition);
        for (Map.Entry<String, DataSource> eachDataSourceEntry : dataSourceMaps.entrySet()) {
            DataSource dataSource1 = eachDataSourceEntry.getValue();
            DatasetDefinition datasetDefinition = mapDatasetDefinition.get(eachDataSourceEntry.getKey());
            Map<String, List<ColumnDefinition>> configs = datasetDefinition.getMetadatas();
            Map<String, List<Map<String, String>>> datas = datasetDefinition.getDatas();
            for (Map.Entry<String, List<Map<String, String>>> eachListEntry : datas.entrySet()) {
                try (Connection conn = dataSource1.getConnection()) {
                    DatabaseUtil.insertUsePreparedStatement(conn, sqls.get(eachListEntry.getKey()),
                            datas.get(eachListEntry.getKey()), configs.get(eachListEntry.getKey()));
                }
            }
        }
    }
    
    public static DataSource getDataSource(final Map<String, DataSource> dataSourceMap, final String path) throws IOException, SQLException {
        return ShardingDataSourceFactory.createDataSource(dataSourceMap, new File(path));
    }
    
    public static DataSource getMasterSlaveDataSource(final Map<String, DataSource> dataSourceMap, final String path) throws IOException, SQLException {
        return MasterSlaveDataSourceFactory.createDataSource(dataSourceMap, new File(path));
    }
}
