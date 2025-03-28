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

import java.text.SimpleDateFormat

suite("test_date_function") {
    def tableName = "test_date_function"

    sql """ SET enable_vectorized_engine = TRUE; """
    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """
            CREATE TABLE IF NOT EXISTS ${tableName} (
                test_datetime datetime NULL COMMENT ""
            ) ENGINE=OLAP
            DUPLICATE KEY(test_datetime)
            COMMENT "OLAP"
            DISTRIBUTED BY HASH(test_datetime) BUCKETS 1
            PROPERTIES (
                "replication_allocation" = "tag.location.default: 1",
                "in_memory" = "false",
                "storage_format" = "V2"
            )
        """
    sql """ insert into ${tableName} values ("2019-08-01 13:21:03") """
    // convert_tz
    qt_sql """ SELECT convert_tz(test_datetime, 'Asia/Shanghai', 'America/Los_Angeles') result from ${tableName}; """
    qt_sql """ SELECT convert_tz(test_datetime, '+08:00', 'America/Los_Angeles') result from ${tableName}; """

    qt_sql """ SELECT convert_tz(test_datetime, 'Asia/Shanghai', 'Europe/London') result from ${tableName}; """
    qt_sql """ SELECT convert_tz(test_datetime, '+08:00', 'Europe/London') result from ${tableName}; """

    qt_sql """ SELECT convert_tz(test_datetime, '+08:00', 'America/London') result from ${tableName}; """

    // some invalid date
    qt_sql """ SELECT convert_tz('2022-2-29 13:21:03', '+08:00', 'America/London') result; """
    qt_sql """ SELECT convert_tz('2022-02-29 13:21:03', '+08:00', 'America/London') result; """
    qt_sql """ SELECT convert_tz('1900-00-00 13:21:03', '+08:00', 'America/London') result; """

    sql """ truncate table ${tableName} """

    // curdate,current_date
    String curdate_str = new SimpleDateFormat("yyyy-MM-dd").format(new Date())
    def curdate_result = sql """ SELECT CURDATE() """
    def curdate_date_result = sql """ SELECT CURRENT_DATE() """
    assertTrue(curdate_str == curdate_result[0][0].toString())
    assertTrue(curdate_str == curdate_date_result[0][0].toString())

    // DATETIME CURRENT_TIMESTAMP()
    def current_timestamp_result = """ SELECT current_timestamp() """
    assertTrue(current_timestamp_result[0].size() == 1)

    // TIME CURTIME()
    def curtime_result = sql """ SELECT CURTIME() """
    assertTrue(curtime_result[0].size() == 1)

    sql """ insert into ${tableName} values ("2010-11-30 23:59:59") """
    // DATE_ADD
    qt_sql """ select date_add(test_datetime, INTERVAL 2 YEAR) result from ${tableName}; """
    qt_sql """ select date_add(test_datetime, INTERVAL 2 MONTH) result from ${tableName}; """
    qt_sql """ select date_add(test_datetime, INTERVAL 2 DAY) result from ${tableName}; """
    qt_sql """ select date_add(test_datetime, INTERVAL 2 HOUR) result from ${tableName}; """
    qt_sql """ select date_add(test_datetime, INTERVAL 2 MINUTE) result from ${tableName}; """
    qt_sql """ select date_add(test_datetime, INTERVAL 2 SECOND) result from ${tableName}; """

    // DATE_FORMAT
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2009-10-04 22:23:00") """
    def resArray = ["Sunday October 2009", "星期日 十月 2009"]
    def res = sql  """ select date_format(test_datetime, '%W %M %Y') from ${tableName}; """
    assertTrue(resArray.contains(res[0][0]))
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2007-10-04 22:23:00") """
    qt_sql """ select date_format(test_datetime, '%H:%i:%s') from ${tableName};"""
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("1900-10-04 22:23:00") """
    qt_sql """ select date_format(test_datetime, '%D %y %a %d %m %b %j') from ${tableName}; """
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("1997-10-04 22:23:00") """
    qt_sql """ select date_format(test_datetime, '%H %k %I %r %T %S %w') from ${tableName}; """
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("1999-01-01 00:00:00") """
    qt_sql """ select date_format(test_datetime, '%X %V') from ${tableName}; """
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2006-06-01") """
    qt_sql """ select date_format(test_datetime, '%d') from ${tableName}; """
    qt_sql """ select date_format(test_datetime, '%%%d') from ${tableName}; """
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2009-10-04 22:23:00") """
    qt_sql """ select date_format(test_datetime, 'yyyy-MM-dd') from ${tableName}; """
    sql """ truncate table ${tableName} """

    sql """ insert into ${tableName} values ("2010-11-30 23:59:59") """
    // DATE_SUB
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 YEAR) from ${tableName};"""
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 MONTH) from ${tableName};"""
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 DAY) from ${tableName};"""
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 HOUR) from ${tableName};"""
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 MINUTE) from ${tableName};"""
    qt_sql """ select date_sub(test_datetime, INTERVAL 2 SECOND) from ${tableName};"""


    // DATEDIFF
    qt_sql """ select datediff(CAST('2007-12-31 23:59:59' AS DATETIME), CAST('2007-12-30' AS DATETIME)) """
    qt_sql """ select datediff(CAST('2010-11-30 23:59:59' AS DATETIME), CAST('2010-12-31' AS DATETIME)) """
    qt_sql """ select datediff('2010-10-31', '2010-10-15') """

    // DAY
    qt_sql """ select day('1987-01-31') """
    qt_sql """ select day('2004-02-29') """

    // DAYNAME
    qt_sql """ select dayname('2007-02-03 00:00:00') """

    // DAYOFMONTH
    qt_sql """ select dayofmonth('1987-01-31') """

    // DAYOFWEEK
    qt_sql """ select dayofweek('2019-06-25') """
    qt_sql """ select dayofweek(cast(20190625 as date)) """

    // DAYOFYEAR
    qt_sql """ select dayofyear('2007-02-03 10:00:00') """
    qt_sql """ select dayofyear('2007-02-03') """

    // FROM_DAYS
    // 通过距离0000-01-01日的天数计算出哪一天
    qt_sql """ select from_days(730669) """
    qt_sql """ select from_days(1) """

    // FROM_UNIXTIME
    qt_sql """ select from_unixtime(1196440219) """
    qt_sql """ select from_unixtime(1196440219, 'yyyy-MM-dd HH:mm:ss') """
    qt_sql """ select from_unixtime(1196440219, '%Y-%m-%d') """
    qt_sql """ select from_unixtime(1196440219, '%Y-%m-%d %H:%i:%s') """
    qt_sql """ select from_unixtime(253402272000, '%Y-%m-%d %H:%i:%s') """

    // HOUR
    qt_sql """ select hour('2018-12-31 23:59:59') """
    qt_sql """ select hour('2018-12-31') """

    // MAKEDATE
    qt_sql """ select makedate(2021,1), makedate(2021,100), makedate(2021,400) """

    // MINUTE
    qt_sql """ select minute('2018-12-31 23:59:59') """
    qt_sql """ select minute('2018-12-31') """

    // MONTH
    qt_sql """ select month('1987-01-01 23:59:59') """
    qt_sql """ select month('1987-01-01') """

    // MONTHNAME
    qt_sql """ select monthname('2008-02-03 00:00:00') """
    qt_sql """ select monthname('2008-02-03') """

    // NOW
    def now_result = sql """ select now() """
    assertTrue(now_result[0].size() == 1)

    // SECOND
    qt_sql """ select second('2018-12-31 23:59:59') """
    qt_sql """ select second('2018-12-31 00:00:00') """

    // STR_TO_DATE
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2014-12-21 12:34:56")  """
    qt_sql """ select str_to_date(test_datetime, '%Y-%m-%d %H:%i:%s') from ${tableName}; """
    qt_sql """ select str_to_date("2014-12-21 12:34%3A56", '%Y-%m-%d %H:%i%%3A%s'); """
    qt_sql """ select str_to_date('200442 Monday', '%X%V %W') """
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2020-09-01")  """
    qt_sql """ select str_to_date(test_datetime, "%Y-%m-%d %H:%i:%s") from ${tableName};"""

    // TIME_ROUND
    qt_sql """ SELECT YEAR_FLOOR('20200202000000') """
    qt_sql """ SELECT MONTH_CEIL(CAST('2020-02-02 13:09:20' AS DATETIME), 3) """
    qt_sql """ SELECT WEEK_CEIL('2020-02-02 13:09:20', '2020-01-06') """
    qt_sql """ SELECT MONTH_CEIL(CAST('2020-02-02 13:09:20' AS DATETIME), 3, CAST('1970-01-09 00:00:00' AS DATETIME)) """

    // TIMEDIFF
    qt_sql """ SELECT TIMEDIFF(now(),utc_timestamp()) """
    qt_sql """ SELECT TIMEDIFF('2019-07-11 16:59:30','2019-07-11 16:59:21') """
    qt_sql """ SELECT TIMEDIFF('2019-01-01 00:00:00', NULL) """

    // TIMESTAMPADD
    sql """ truncate table ${tableName} """
    sql """ insert into ${tableName} values ("2019-01-02") ; """
    qt_sql """ SELECT TIMESTAMPADD(YEAR,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(MONTH,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(WEEK,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(DAY,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(HOUR,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(MINUTE,1,test_datetime) from ${tableName}; """
    qt_sql """ SELECT TIMESTAMPADD(SECOND,1,test_datetime) from ${tableName}; """

    // TIMESTAMPDIFF
    qt_sql """ SELECT TIMESTAMPDIFF(MONTH,'2003-02-01','2003-05-01') """
    qt_sql """ SELECT TIMESTAMPDIFF(YEAR,'2002-05-01','2001-01-01') """
    qt_sql """ SELECT TIMESTAMPDIFF(MINUTE,'2003-02-01','2003-05-01 12:05:55') """
    qt_sql """ SELECT TIMESTAMPDIFF(SECOND,'2003-02-01','2003-05-01') """
    qt_sql """ SELECT TIMESTAMPDIFF(HOUR,'2003-02-01','2003-05-01') """
    qt_sql """ SELECT TIMESTAMPDIFF(DAY,'2003-02-01','2003-05-01') """
    qt_sql """ SELECT TIMESTAMPDIFF(WEEK,'2003-02-01','2003-05-01') """

    // TO_DAYS
    qt_sql """ select to_days('2007-10-07') """
    qt_sql """ select to_days('2050-10-07') """

    // UNIX_TIMESTAMP
    def unin_timestamp_str = """ select unix_timestamp() """
    assertTrue(unin_timestamp_str[0].size() == 1)
    qt_sql """ select unix_timestamp('2007-11-30 10:30:19') """
    qt_sql """ select unix_timestamp('2007-11-30 10:30-19', '%Y-%m-%d %H:%i-%s') """
    qt_sql """ select unix_timestamp('2007-11-30 10:30%3A19', '%Y-%m-%d %H:%i%%3A%s') """
    qt_sql """ select unix_timestamp('1969-01-01 00:00:00') """

    // UTC_TIMESTAMP
    def utc_timestamp_str = sql """ select utc_timestamp(),utc_timestamp() + 1 """
    assertTrue(utc_timestamp_str[0].size() == 2)
    // WEEK
    qt_sql """ select week('2020-1-1') """
    qt_sql """ select week('2020-7-1',1) """

    // WEEKDAY
    qt_sql """ select weekday('2019-06-25'); """
    qt_sql """ select weekday(cast(20190625 as date)); """

    // WEEKOFYEAR
    qt_sql """ select weekofyear('2008-02-20 00:00:00') """

    // YEAR
    qt_sql """ select year('1987-01-01') """
    qt_sql """ select year('2050-01-01') """

    // YEARWEEK
    qt_sql """ select yearweek('2021-1-1') """
    qt_sql """ select yearweek('2020-7-1') """
    qt_sql """ select yearweek('1989-03-21', 0) """
    qt_sql """ select yearweek('1989-03-21', 1) """
    qt_sql """ select yearweek('1989-03-21', 2) """
    qt_sql """ select yearweek('1989-03-21', 3) """
    qt_sql """ select yearweek('1989-03-21', 4) """
    qt_sql """ select yearweek('1989-03-21', 5) """
    qt_sql """ select yearweek('1989-03-21', 6) """
    qt_sql """ select yearweek('1989-03-21', 7) """

    qt_sql """ select count(*) from (select * from numbers("200")) tmp1 WHERE 0 <= UNIX_TIMESTAMP(); """

    sql """ drop table ${tableName} """

    tableName = "test_from_unixtime"

    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """
            CREATE TABLE IF NOT EXISTS ${tableName} (
                `id` INT NOT NULL COMMENT "用户id",
                `update_time` INT NOT NULL COMMENT "数据灌入日期时间"
            ) ENGINE=OLAP
            UNIQUE KEY(`id`)
            DISTRIBUTED BY HASH(`id`)
            PROPERTIES("replication_num" = "1");
        """
    sql """ insert into ${tableName} values (1, 1659344431) """
    sql """ insert into ${tableName} values (2, 1659283200) """
    sql """ insert into ${tableName} values (3, 1659283199) """
    sql """ insert into ${tableName} values (4, 1659283201) """

    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") = '2022-08-01' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") > '2022-08-01' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") < '2022-08-01' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") >= '2022-08-01' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") <= '2022-08-01' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d") LIKE '2022-08-01' ORDER BY id; """

    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") = '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") > '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") < '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") >= '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") <= '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") LIKE '2022-08-01 00:00:00' ORDER BY id; """
    qt_sql """ SELECT id,FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") FROM ${tableName} WHERE FROM_UNIXTIME(update_time,"%Y-%m-%d %H:%i:%s") = '2022-08-01 17:00:31' ORDER BY id; """

    qt_sql """SELECT CURDATE() = CURRENT_DATE();"""
    qt_sql """SELECT unix_timestamp(CURDATE()) = unix_timestamp(CURRENT_DATE());"""

    sql """ drop table ${tableName} """

    qt_sql """ select date_format('2022-08-04', '%X %V %w'); """
    qt_sql """ select STR_TO_DATE('Tue Jul 12 20:00:45 CST 2022', '%a %b %e %H:%i:%s %Y'); """
    qt_sql """ select STR_TO_DATE('Tue Jul 12 20:00:45 CST 2022', '%a %b %e %T CST %Y'); """
    qt_sql """ select STR_TO_DATE('2018-4-2 15:3:28','%Y-%m-%d %H:%i:%s'); """

    qt_sql """ select length(cast(now() as string)), length(cast(now(0) as string)), length(cast(now(1) as string)),
                      length(cast(now(2) as string)), length(cast(now(3) as string)), length(cast(now(4) as string)),
                      length(cast(now(5) as string)), length(cast(now(6) as string)); """
    qt_sql """ select length(cast(current_timestamp() as string)), length(cast(current_timestamp(0) as string)),
                      length(cast(current_timestamp(1) as string)), length(cast(current_timestamp(2) as string)),
                      length(cast(current_timestamp(3) as string)), length(cast(current_timestamp(4) as string)),
                      length(cast(current_timestamp(5) as string)), length(cast(current_timestamp(6) as string)); """
}
