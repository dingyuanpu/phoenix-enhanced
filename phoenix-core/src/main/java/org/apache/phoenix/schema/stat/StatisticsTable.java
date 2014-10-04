/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.schema.stat;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Date;
import java.util.List;

import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.MultiRowMutationProtocol;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.TrustedByteArrayOutputStream;

/**
 * Wrapper to access the statistics table SYSTEM.STATS using the HTable.
 */
public class StatisticsTable implements Closeable {
    /**
     * @param env
     *            Environment wherein the coprocessor is attempting to update the stats table.
     * @param primaryTableName
     *            name of the primary table on which we should collect stats
     * @return the {@link StatisticsTable} for the given primary table.
     * @throws IOException
     *             if the table cannot be created due to an underlying HTable creation error
     */
    public static StatisticsTable getStatisticsTable(HTableInterface hTable) throws IOException {
        return new StatisticsTable(hTable);
    }

    private final HTableInterface statisticsTable;

    private StatisticsTable(HTableInterface statsTable) {
        this.statisticsTable = statsTable;
    }

    /**
     * Close the connection to the table
     */
    @Override
    public void close() throws IOException {
        statisticsTable.close();
    }

    /**
     * Update a list of statistics for a given region.  If the ANALYZE <tablename> query is issued
     * then we use Upsert queries to update the table
     * If the region gets splitted or the major compaction happens we update using HTable.put()
     * @param tablekey - The table name
     * @param schemaName - the schema name associated with the table          
     * @param region name -  the region of the table for which the stats are collected
     * @param tracker - the statistics tracker
     * @param fam -  the family for which the stats is getting collected.
     * @param split - if the updation is caused due to a split
     * @param mutations - list of mutations that collects all the mutations to commit in a batch
     * @param currentTime -  the current time
     * @throws IOException
     *             if we fail to do any of the puts. Any single failure will prevent any future attempts for the remaining list of stats to
     *             update
     */
    public void addStats(String tableName, String regionName, StatisticsCollector tracker, String fam,
            List<Mutation> mutations, long currentTime) throws IOException {
        if (tracker == null) { return; }

        // Add the timestamp header
        commitLastStatsUpdatedTime(tableName, currentTime);

        byte[] prefix = StatisticsUtils.getRowKey(PDataType.VARCHAR.toBytes(tableName), PDataType.VARCHAR.toBytes(fam),
                PDataType.VARCHAR.toBytes(regionName));
        formStatsUpdateMutation(tracker, fam, mutations, currentTime, prefix);
    }

    public void commitStats(List<Mutation> mutations) throws IOException {
        if (mutations.size() > 0) {
            byte[] row = mutations.get(0).getRow();
            HTableInterface stats = this.statisticsTable;
            MultiRowMutationProtocol protocol = stats.coprocessorProxy(MultiRowMutationProtocol.class, row);
            protocol.mutateRows(mutations);
        }
    }

    private void formStatsUpdateMutation(StatisticsCollector tracker, String fam, List<Mutation> mutations,
            long currentTime, byte[] prefix) {
        Put put = new Put(prefix, currentTime);
        if (tracker.getGuidePosts(fam) != null) {
            put.add(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, PhoenixDatabaseMetaData.GUIDE_POSTS_BYTES,
                    currentTime, (tracker.getGuidePosts(fam)));
        }
        put.add(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, PhoenixDatabaseMetaData.MIN_KEY_BYTES,
                currentTime, PDataType.VARBINARY.toBytes(tracker.getMinKey(fam)));
        put.add(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, PhoenixDatabaseMetaData.MAX_KEY_BYTES,
                currentTime, PDataType.VARBINARY.toBytes(tracker.getMaxKey(fam)));
        // Add our empty column value so queries behave correctly
        put.add(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, QueryConstants.EMPTY_COLUMN_BYTES,
                currentTime, ByteUtil.EMPTY_BYTE_ARRAY);
        mutations.add(put);
    }

    public static byte[] getRowKeyForTSUpdate(byte[] table) throws IOException {
        // always starts with the source table
        TrustedByteArrayOutputStream os = new TrustedByteArrayOutputStream(table.length);
        os.write(table);
        os.close();
        return os.getBuffer();
    }

    public void commitLastStatsUpdatedTime(String tableName, long currentTime) throws IOException {
        byte[] prefix = PDataType.VARCHAR.toBytes(tableName);
        Put put = new Put(prefix);
        put.add(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES, PhoenixDatabaseMetaData.LAST_STATS_UPDATE_TIME_BYTES, currentTime,
                PDataType.DATE.toBytes(new Date(currentTime)));
        statisticsTable.put(put);
    }
    
    public void deleteStats(String tableName, String regionName, StatisticsCollector tracker, String fam,
            List<Mutation> mutations, long currentTime)
            throws IOException {
        byte[] prefix = StatisticsUtils.getRowKey(PDataType.VARCHAR.toBytes(tableName), PDataType.VARCHAR.toBytes(fam),
                PDataType.VARCHAR.toBytes(regionName));
        mutations.add(new Delete(prefix, currentTime - 1));
    }
}