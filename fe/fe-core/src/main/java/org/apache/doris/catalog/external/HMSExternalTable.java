// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog.external;

import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.HiveMetaStoreClientHelper;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.datasource.HMSExternalCatalog;
import org.apache.doris.thrift.THiveTable;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableType;

import com.google.common.collect.Lists;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hive metastore external table.
 */
public class HMSExternalTable extends ExternalTable {

    private static final Logger LOG = LogManager.getLogger(HMSExternalTable.class);

    private final HMSExternalCatalog catalog;
    private final String dbName;
    private final List<String> supportedHiveFileFormats = Lists.newArrayList(
            "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
            "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
            "org.apache.hadoop.mapred.TextInputFormat");

    private volatile org.apache.hadoop.hive.metastore.api.Table remoteTable = null;
    private DLAType dlaType = DLAType.UNKNOWN;
    private boolean initialized = false;

    public enum DLAType {
        UNKNOWN, HIVE, HUDI, ICEBERG
    }

    /**
     * Create hive metastore external table.
     *
     * @param id Table id.
     * @param name Table name.
     * @param dbName Database name.
     * @param catalog HMSExternalCatalog.
     */
    public HMSExternalTable(long id, String name, String dbName, HMSExternalCatalog catalog) {
        super(id, name);
        this.dbName = dbName;
        this.catalog = catalog;
        this.type = TableType.HMS_EXTERNAL_TABLE;
    }

    public boolean isSupportedHmsTable() {
        makeSureInitialized();
        return dlaType != DLAType.UNKNOWN;
    }

    private synchronized void makeSureInitialized() {
        if (!initialized) {
            init();
            initialized = true;
        }
    }

    private void init() {
        try {
            getRemoteTable();
        } catch (MetaNotFoundException e) {
            // CHECKSTYLE IGNORE THIS LINE
        }
        if (remoteTable == null) {
            dlaType = DLAType.UNKNOWN;
            fullSchema = Lists.newArrayList();
        } else {
            if (supportedIcebergTable()) {
                dlaType = DLAType.ICEBERG;
            } else if (supportedHoodieTable()) {
                dlaType = DLAType.HUDI;
            } else if (supportedHiveTable()) {
                dlaType = DLAType.HIVE;
            } else {
                dlaType = DLAType.UNKNOWN;
                fullSchema = Lists.newArrayList();
            }
            initSchema();
        }
    }

    /**
     * Now we only support cow table in iceberg.
     */
    private boolean supportedIcebergTable() {
        Map<String, String> paras = remoteTable.getParameters();
        if (paras == null) {
            return false;
        }
        boolean isIcebergTable = paras.containsKey("table_type")
                && paras.get("table_type").equalsIgnoreCase("ICEBERG");
        boolean isMorInDelete = paras.containsKey("write.delete.mode")
                && paras.get("write.delete.mode").equalsIgnoreCase("merge-on-read");
        boolean isMorInUpdate = paras.containsKey("write.update.mode")
                && paras.get("write.update.mode").equalsIgnoreCase("merge-on-read");
        boolean isMorInMerge = paras.containsKey("write.merge.mode")
                && paras.get("write.merge.mode").equalsIgnoreCase("merge-on-read");
        boolean isCowTable = !(isMorInDelete || isMorInUpdate || isMorInMerge);
        return isIcebergTable && isCowTable;
    }

    /**
     * Now we only support `Snapshot Queries` on both cow and mor table and `Read Optimized Queries` on cow table.
     * And they both use the `HoodieParquetInputFormat` for the input format in hive metastore.
     */
    private boolean supportedHoodieTable() {
        if (remoteTable.getSd() == null) {
            return false;
        }
        String inputFormatName = remoteTable.getSd().getInputFormat();
        return inputFormatName != null
                && inputFormatName.equalsIgnoreCase("org.apache.hudi.hadoop.HoodieParquetInputFormat");
    }

    /**
     * Now we only support three file input format hive tables: parquet/orc/text. And they must be managed_table.
     */
    private boolean supportedHiveTable() {
        boolean isManagedTable = remoteTable.getTableType().equalsIgnoreCase("MANAGED_TABLE");
        String inputFileFormat = remoteTable.getSd().getInputFormat();
        boolean supportedFileFormat = inputFileFormat != null && supportedHiveFileFormats.contains(inputFileFormat);
        return isManagedTable && supportedFileFormat;
    }

    private void initSchema() {
        if (fullSchema == null) {
            synchronized (this) {
                if (fullSchema == null) {
                    fullSchema = Lists.newArrayList();
                    try {
                        for (FieldSchema field : HiveMetaStoreClientHelper.getSchema(dbName, name,
                                catalog.getHiveMetastoreUris())) {
                            fullSchema.add(new Column(field.getName(),
                                    HiveMetaStoreClientHelper.hiveTypeToDorisType(field.getType()), true, null, true,
                                    null, field.getComment()));
                        }
                    } catch (DdlException e) {
                        LOG.warn("Fail to get schema of hms table {}", name, e);
                    }
                }
            }
        }
    }

    /**
     * Get the related remote hive metastore table.
     */
    public org.apache.hadoop.hive.metastore.api.Table getRemoteTable() throws MetaNotFoundException {
        if (remoteTable == null) {
            synchronized (this) {
                if (remoteTable == null) {
                    try {
                        remoteTable = HiveMetaStoreClientHelper.getTable(dbName, name, catalog.getHiveMetastoreUris());
                    } catch (DdlException e) {
                        LOG.warn("Fail to get remote hive table. db {}, table {}, uri {}", dbName, name,
                                catalog.getHiveMetastoreUris());
                        throw new MetaNotFoundException(e);
                    }
                }
            }
        }
        // TODO: Refresh cached remoteTable
        return remoteTable;
    }

    @Override
    public boolean isView() {
        return remoteTable.isSetViewOriginalText() || remoteTable.isSetViewExpandedText();
    }

    @Override
    public List<Column> getFullSchema() {
        makeSureInitialized();
        return fullSchema;
    }

    @Override
    public List<Column> getBaseSchema() {
        return getFullSchema();
    }

    @Override
    public List<Column> getBaseSchema(boolean full) {
        return getFullSchema();
    }

    @Override
    public Column getColumn(String name) {
        makeSureInitialized();
        for (Column column : fullSchema) {
            if (name.equals(column.getName())) {
                return column;
            }
        }
        return null;
    }

    @Override
    public String getMysqlType() {
        return type.name();
    }

    @Override
    public String getEngine() {
        switch (type) {
            case HIVE:
                return "Hive";
            case ICEBERG:
                return "Iceberg";
            case HUDI:
                return "Hudi";
            default:
                return null;
        }
    }

    @Override
    public String getComment() {
        return "";
    }

    @Override
    public long getCreateTime() {
        return 0;
    }

    @Override
    public long getUpdateTime() {
        return 0;
    }

    @Override
    public long getRowCount() {
        return 0;
    }

    @Override
    public long getDataLength() {
        return 0;
    }

    @Override
    public long getAvgRowLength() {
        return 0;
    }

    public long getLastCheckTime() {
        return 0;
    }

    /**
     * get database name of hms table.
     */
    public String getDbName() {
        return dbName;
    }

    /**
     * get the dla type for scan node to get right information.
     */
    public DLAType getDlaType() {
        return dlaType;
    }

    @Override
    public TTableDescriptor toThrift() {
        THiveTable tHiveTable = new THiveTable(dbName, name, new HashMap<>());
        TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.HIVE_TABLE, fullSchema.size(), 0,
                getName(), dbName);
        tTableDescriptor.setHiveTable(tHiveTable);
        return tTableDescriptor;
    }

    public String getMetastoreUri() {
        return catalog.getHiveMetastoreUris();
    }

    public Map<String, String> getDfsProperties() {
        return catalog.getCatalogProperty().getDfsProperties();
    }

    public Map<String, String> getS3Properties() {
        return catalog.getCatalogProperty().getS3Properties();
    }
}
