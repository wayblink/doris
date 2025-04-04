      
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

import org.codehaus.groovy.runtime.IOGroovyMethods

suite ("test_agg_rollup_schema_change") {
    def tableName = "schema_change_agg_rollup_regression_test"

    try {

        String[][] backends = sql """ show backends; """
        assertTrue(backends.size() > 0)
        String backend_id;
        def backendId_to_backendIP = [:]
        def backendId_to_backendHttpPort = [:]
        for (String[] backend in backends) {
            backendId_to_backendIP.put(backend[0], backend[2])
            backendId_to_backendHttpPort.put(backend[0], backend[5])
        }

        backend_id = backendId_to_backendIP.keySet()[0]
        StringBuilder showConfigCommand = new StringBuilder();
        showConfigCommand.append("curl -X GET http://")
        showConfigCommand.append(backendId_to_backendIP.get(backend_id))
        showConfigCommand.append(":")
        showConfigCommand.append(backendId_to_backendHttpPort.get(backend_id))
        showConfigCommand.append("/api/show_config")
        logger.info(showConfigCommand.toString())
        def process = showConfigCommand.toString().execute()
        int code = process.waitFor()
        String err = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(process.getErrorStream())));
        String out = process.getText()
        logger.info("Show config: code=" + code + ", out=" + out + ", err=" + err)
        assertEquals(code, 0)
        def configList = parseJson(out.trim())
        assert configList instanceof List

        boolean disableAutoCompaction = true
        for (Object ele in (List) configList) {
            assert ele instanceof List<String>
            if (((List<String>) ele)[0] == "disable_auto_compaction") {
                disableAutoCompaction = Boolean.parseBoolean(((List<String>) ele)[2])
            }
        }

        sql """ DROP TABLE IF EXISTS ${tableName} """
        sql """
                CREATE TABLE ${tableName} (
                    `user_id` LARGEINT NOT NULL COMMENT "用户id",
                    `date` DATE NOT NULL COMMENT "数据灌入日期时间",
                    `city` VARCHAR(20) COMMENT "用户所在城市",
                    `age` SMALLINT COMMENT "用户年龄",
                    `sex` TINYINT COMMENT "用户性别",

                    `cost` BIGINT SUM DEFAULT "0" COMMENT "用户总消费",
                    `max_dwell_time` INT MAX DEFAULT "0" COMMENT "用户最大停留时间",
                    `min_dwell_time` INT MIN DEFAULT "99999" COMMENT "用户最小停留时间",
                    `hll_col` HLL HLL_UNION NOT NULL COMMENT "HLL列",
                    `bitmap_col` Bitmap BITMAP_UNION NOT NULL COMMENT "bitmap列")
                AGGREGATE KEY(`user_id`, `date`, `city`, `age`, `sex`) DISTRIBUTED BY HASH(`user_id`)
                BUCKETS 1
                PROPERTIES ( "replication_num" = "1", "light_schema_change" = "true" );
            """

        //add rollup
        def result = "null"
        def rollupName = "rollup_cost"
        sql "ALTER TABLE ${tableName} ADD ROLLUP ${rollupName}(`user_id`,`date`,`city`,`age`,`sex`, cost);"
        while (!result.contains("FINISHED")){
            result = sql "SHOW ALTER TABLE ROLLUP WHERE TableName='${tableName}' ORDER BY CreateTime DESC LIMIT 1;"
            result = result.toString()
            logger.info("result: ${result}")
            if(result.contains("CANCELLED")){
                return
            }
            Thread.sleep(100)
        }

        sql """ INSERT INTO ${tableName} VALUES
                (1, '2017-10-01', 'Beijing', 10, 1, 1, 30, 20, hll_hash(1), to_bitmap(1))
            """
        sql """ INSERT INTO ${tableName} VALUES
                (1, '2017-10-01', 'Beijing', 10, 1, 1, 31, 19, hll_hash(2), to_bitmap(2))
            """
        sql """ INSERT INTO ${tableName} VALUES
                (2, '2017-10-01', 'Beijing', 10, 1, 1, 31, 21, hll_hash(2), to_bitmap(2))
            """
        sql """ INSERT INTO ${tableName} VALUES
                (2, '2017-10-01', 'Beijing', 10, 1, 1, 32, 20, hll_hash(3), to_bitmap(3))
            """

        qt_sc """ select * from ${tableName} order by user_id """

        // drop value column with rollup, not light schema change
        sql """
            ALTER TABLE ${tableName} DROP COLUMN cost
            """

        result = "null"
        while (!result.contains("FINISHED")){
            result = sql "SHOW ALTER TABLE COLUMN WHERE IndexName='${tableName}' ORDER BY CreateTime DESC LIMIT 1;"
            result = result.toString()
            logger.info("result: ${result}")
            if(result.contains("CANCELLED")) {
                log.info("rollup job is cancelled, result: ${result}".toString())
                return
            }
            Thread.sleep(100)
        }

        sql """ INSERT INTO ${tableName} (`user_id`, `date`, `city`, `age`, `sex`, `max_dwell_time`,`min_dwell_time`, `hll_col`, `bitmap_col`)
                VALUES
                (3, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(4), to_bitmap(4))
            """
        qt_sc """ SELECT * FROM ${tableName} WHERE user_id = 3 """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        sql """ INSERT INTO ${tableName} VALUES
                (5, '2017-10-01', 'Beijing', 10, 1, 32, 20, hll_hash(5), to_bitmap(5))
            """

        // compaction
        String[][] tablets = sql """ show tablets from ${tableName}; """
        for (String[] tablet in tablets) {
                String tablet_id = tablet[0]
                backend_id = tablet[2]
                logger.info("run compaction:" + tablet_id)
                StringBuilder sb = new StringBuilder();
                sb.append("curl -X POST http://")
                sb.append(backendId_to_backendIP.get(backend_id))
                sb.append(":")
                sb.append(backendId_to_backendHttpPort.get(backend_id))
                sb.append("/api/compaction/run?tablet_id=")
                sb.append(tablet_id)
                sb.append("&compact_type=cumulative")

                String command = sb.toString()
                process = command.execute()
                code = process.waitFor()
                err = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(process.getErrorStream())));
                out = process.getText()
                logger.info("Run compaction: code=" + code + ", out=" + out + ", err=" + err)
                //assertEquals(code, 0)
        }

        // wait for all compactions done
        for (String[] tablet in tablets) {
                boolean running = true
                do {
                    Thread.sleep(100)
                    String tablet_id = tablet[0]
                    backend_id = tablet[2]
                    StringBuilder sb = new StringBuilder();
                    sb.append("curl -X GET http://")
                    sb.append(backendId_to_backendIP.get(backend_id))
                    sb.append(":")
                    sb.append(backendId_to_backendHttpPort.get(backend_id))
                    sb.append("/api/compaction/run_status?tablet_id=")
                    sb.append(tablet_id)

                    String command = sb.toString()
                    process = command.execute()
                    code = process.waitFor()
                    err = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(process.getErrorStream())));
                    out = process.getText()
                    logger.info("Get compaction status: code=" + code + ", out=" + out + ", err=" + err)
                    assertEquals(code, 0)
                    def compactionStatus = parseJson(out.trim())
                    assertEquals("success", compactionStatus.status.toLowerCase())
                    running = compactionStatus.run_status
                } while (running)
        }
         
        qt_sc """ select count(*) from ${tableName} """

        qt_sc """  SELECT * FROM ${tableName} WHERE user_id=2 """

    } finally {
            //try_sql("DROP TABLE IF EXISTS ${tableName}")
    }
}
